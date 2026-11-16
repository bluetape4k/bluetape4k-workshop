@file:Suppress("MaxLineLength", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeInstanceOf
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.jdbc.sql.singleLong
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.JdbcVoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.persistence.BatchRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.CommittedOrdinalDigest
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcVoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.security.AesGcmVoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKey
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKeyRing
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.EncryptedVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.security.EntryIdentity
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoException
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoFailureReason
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigest
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKek
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.core.io.ClassPathResource
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.collections.ArrayDeque
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Suppress("LargeClass")
internal class VoucherBatchIngestIntegrationTest {

    companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
        private val POSTGRES_ROUNDING_INSTANT = Instant.parse("2026-07-21T13:47:39.999999789Z")
        private const val TENANT = "tenant-ingest"
        private const val TERMINAL_RACE_THREAD = "failing-create-terminal-race"
    }

    private val voucherSchema = "voucher_ingest_${Base58.randomString(8).lowercase()}"
    private val dataSource = postgres.getHikariDataSource {
        schema = voucherSchema
    }
    private lateinit var digests: VoucherDigestService
    private lateinit var service: JdbcCampaignBatchCommandService
    private lateinit var crypto: VoucherEnvelopeCrypto

    @BeforeAll
    fun migrate() {
        adminConnection().use {
            it.createStatement().execute("CREATE SCHEMA $voucherSchema")
        }

        VoucherPoolMigrationRunner(
            dataSource,
            VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            537006L,
        ).migrate()

        digests = digestService()
        crypto = BoundaryCheckingCrypto(
            AesGcmVoucherEnvelopeCrypto(
                VoucherKekRing.of(VoucherKek.of("test-kek", keyBytes(11))),
                digests,
            ),
        )
        service = newService()
    }

    @AfterAll
    fun cleanup() {
        adminConnection().use {
            it.createStatement().execute("DROP SCHEMA IF EXISTS $voucherSchema CASCADE")
        }
        dataSource.close()
    }

    @BeforeEach
    fun reset() {
        dataSource.connection.use {
            it.createStatement()
                .execute(
                    "TRUNCATE voucher_pool_http_idempotency,voucher_pool_command_tombstones,voucher_pool_entries,voucher_pool_code_dedup,voucher_pool_batches,voucher_pool_campaigns CASCADE",
                )
        }
        service = newService()
    }

    @Test
    fun `import checkpoint commits ordinal digest and counts then resumes after restart`() {
        val fixture = campaignAndBatch("resume", expected = 6, initial = codes(0, 2))
        val first = fixture.batch

        log.debug { "first: $first" }
        first.nextSourceOrdinal shouldBeEqualTo 2
        first.acceptedCount shouldBeEqualTo 2
        first.rejectedCount shouldBeEqualTo 0

        service = newService()

        val second = service.importChunk(import(fixture, 2, codes(2, 2))).applied()
        log.debug { "second: $second" }
        second.nextSourceOrdinal shouldBeEqualTo 4
        second.acceptedCount shouldBeEqualTo 4
        second.checkpointDigest shouldNotBeEqualTo first.checkpointDigest

        service.importChunk(
            import(
                fixture = fixture,
                firstOrdinal = 0,
                codes = codes(0, 2),
                expectedRevision = second.revision
            )
        ).applied().nextSourceOrdinal shouldBeEqualTo 4

        assertFailsWith<BatchCommandException> {
            service.importChunk(
                import(
                    fixture = fixture,
                    firstOrdinal = 0,
                    codes = listOf("CHANGED-0", "CHANGED-1"),
                    expectedRevision = second.revision
                )
            )
        }.reason shouldBeEqualTo BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT

        assertFailsWith<BatchCommandException> {
            service.importChunk(
                ImportChunkCommand(
                    tenantId = TENANT,
                    batchId = fixture.batch.batchId,
                    campaignId = fixture.campaign.campaignId,
                    firstOrdinal = 0,
                    manifestDigest = manifest("resume"),
                    codes = listOf("CHANGED-0", "CHANGED-1"),
                    expectedRevision = second.revision,
                    idempotencyKey = key("changed-committed-chunk"),
                ),
            )
        }.reason shouldBeEqualTo BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT
        entryCount(fixture.batch.batchId) shouldBeEqualTo 4
    }

    @Test
    fun `every campaign and batch mutation replays a safe descriptor without fabricating a snapshot`() {
        val createCampaign = createCampaignCommand("all-mutations")
        val draft = service.createCampaign(createCampaign).applied()
        service.createCampaign(createCampaign)
            .shouldReplay(createCampaign.campaignId, draft.revision, "CAMPAIGN_CREATED")

        val update = UpdateCampaignPolicyCommand(
            tenantId = TENANT,
            campaignId = draft.campaignId,
            expectedRevision = draft.revision,
            policy = VoucherPoolPolicy(3, 1.minutes, 5.minutes, 1),
            idempotencyKey = key("policy-all-mutations"),
        )

        val updated = service.updatePolicy(update).applied()
        log.debug { "updated: $updated" }
        service.updatePolicy(update)
            .shouldReplay(
                effectId = updated.campaignId,
                revision = updated.revision,
                outcome = "CAMPAIGN_POLICY_UPDATED"
            )

        val activate = CampaignRevisionCommand(
            tenantId = TENANT,
            campaignId = updated.campaignId,
            expectedRevision = updated.revision,
            idempotencyKey = key("activate-all-mutations")
        )
        val campaign = service.activateCampaign(activate).applied()
        service.activateCampaign(activate).shouldReplay(
            effectId = campaign.campaignId,
            revision = campaign.revision,
            outcome = "CAMPAIGN_ACTIVATED"
        )

        val createBatch = createBatchCommand(
            campaign = campaign,
            suffix = "all-mutations",
            expected = 2,
            initial = listOf("ALL-0")
        )
        val batch = service.createImportBatch(createBatch).applied()
        service.createImportBatch(createBatch)
            .shouldReplay(
                effectId = batch.batchId,
                revision = batch.revision,
                outcome = "BATCH_CREATED"
            )

        val chunk = ImportChunkCommand(
            tenantId = TENANT,
            batchId = batch.batchId,
            campaignId = campaign.campaignId,
            firstOrdinal = 1,
            manifestDigest = manifest("all-mutations"),
            codes = listOf("ALL-1"),
            expectedRevision = batch.revision,
            idempotencyKey = key("chunk-all-mutations"),
        )
        val completed = service.importChunk(chunk).applied()
        service.importChunk(chunk)
            .shouldReplay(
                effectId = batch.batchId,
                revision = completed.revision,
                outcome = "BATCH_CHECKPOINTED"
            )

        val activateBatch = BatchRevisionCommand(
            tenantId = TENANT,
            campaignId = campaign.campaignId,
            batchId = batch.batchId,
            expectedRevision = completed.revision,
            idempotencyKey = key("activate-batch-all-mutations"),
        )
        val active = service.activateBatch(activateBatch).applied()
        service.activateBatch(activateBatch)
            .shouldReplay(
                effectId = batch.batchId,
                revision = active.revision,
                outcome = "BATCH_ACTIVATED"
            )

        val generatedBatch = createBatch(
            campaign = campaign,
            suffix = "all-mutations-generated",
            expected = 1,
            initial = emptyList(),
            sourceKind = BatchSourceKind.GENERATED
        )
        val generate = GenerateChunkCommand(
            tenantId = TENANT,
            batchId = generatedBatch.batchId,
            campaignId = campaign.campaignId,
            firstOrdinal = 0,
            manifestDigest = manifest("all-mutations-generated"),
            count = 1,
            expectedRevision = generatedBatch.revision,
            idempotencyKey = key("generate-all-mutations"),
        )
        val generated = service.generateChunk(generate).applied()

        service.generateChunk(generate)
            .shouldReplay(
                effectId = generatedBatch.batchId,
                revision = generated.revision,
                outcome = "BATCH_CHECKPOINTED"
            )
    }

    @Test
    fun `import expected revision participates in the idempotency fingerprint`() {
        val fixture = campaignAndBatch("import-revision-fingerprint", expected = 2, initial = listOf("IMPORT-FP-0"))
        val command = ImportChunkCommand(
            tenantId = TENANT,
            batchId = fixture.batch.batchId,
            campaignId = fixture.campaign.campaignId,
            firstOrdinal = 1,
            manifestDigest = manifest("import-revision-fingerprint"),
            codes = listOf("IMPORT-FP-1"),
            expectedRevision = fixture.batch.revision,
            idempotencyKey = key("import-revision-fingerprint"),
        )
        val applied = service.importChunk(command).applied()

        assertFailsWith<BatchCommandException> {
            service.importChunk(
                ImportChunkCommand(
                    tenantId = command.tenantId,
                    batchId = command.batchId,
                    campaignId = command.campaignId,
                    firstOrdinal = command.firstOrdinal,
                    manifestDigest = command.manifestDigest,
                    codes = command.codes,
                    expectedRevision = applied.revision,
                    idempotencyKey = command.idempotencyKey,
                ),
            )
        }.reason shouldBeEqualTo BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT
    }

    @Test
    fun `generation expected revision participates in the idempotency fingerprint`() {
        val campaign = createCampaign("generate-revision-fingerprint")
        val batch = createBatch(
            campaign = campaign,
            suffix = "generate-revision-fingerprint",
            expected = 1,
            initial = emptyList(),
            sourceKind = BatchSourceKind.GENERATED,
        )
        val command = GenerateChunkCommand(
            tenantId = TENANT,
            batchId = batch.batchId,
            campaignId = campaign.campaignId,
            firstOrdinal = 0,
            manifestDigest = manifest("generate-revision-fingerprint"),
            count = 1,
            expectedRevision = batch.revision,
            idempotencyKey = key("generate-revision-fingerprint"),
        )
        val applied = service.generateChunk(command).applied()

        assertFailsWith<BatchCommandException> {
            service.generateChunk(command.copy(expectedRevision = applied.revision))
        }.reason shouldBeEqualTo BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT
    }

    @Test
    fun `same import revision admits one concurrent chunk and rejects the other as stale`() {
        val fixture = campaignAndBatch("concurrent-import-revision", expected = 3, initial = listOf("IMPORT-INITIAL"))
        val commands = listOf(
            ImportChunkCommand(
                tenantId = TENANT,
                batchId = fixture.batch.batchId,
                campaignId = fixture.campaign.campaignId,
                firstOrdinal = 1,
                manifestDigest = manifest("concurrent-import-revision"),
                codes = listOf("IMPORT-WINNER-A"),
                expectedRevision = fixture.batch.revision,
                idempotencyKey = key("concurrent-import-revision-a"),
            ),
            ImportChunkCommand(
                tenantId = TENANT,
                batchId = fixture.batch.batchId,
                campaignId = fixture.campaign.campaignId,
                firstOrdinal = 1,
                manifestDigest = manifest("concurrent-import-revision"),
                codes = listOf("IMPORT-WINNER-B"),
                expectedRevision = fixture.batch.revision,
                idempotencyKey = key("concurrent-import-revision-b"),
            ),
        )

        val outcomes = executeConcurrently(commands) {
            service.importChunk(it).applied()
        }

        outcomes.filterIsInstance<BatchSnapshot>() shouldHaveSize 1
        outcomes.count { it == BatchCommandFailure.STALE_REVISION } shouldBeEqualTo 1

        val stored = batchRow(fixture.batch.batchId)
        log.debug { "stored: $stored" }
        stored.revision shouldBeEqualTo fixture.batch.revision + 1
        stored.nextSourceOrdinal shouldBeEqualTo 2
        entryCount(fixture.batch.batchId) shouldBeEqualTo 2
    }

    @Test
    fun `same generation revision admits one concurrent chunk and rejects the other as stale`() {
        val campaign = createCampaign("concurrent-generate-revision")
        val batch = createBatch(
            campaign,
            "concurrent-generate-revision",
            expected = 3,
            initial = emptyList(),
            sourceKind = BatchSourceKind.GENERATED,
        )
        val commands = listOf(
            GenerateChunkCommand(
                tenantId = TENANT,
                batchId = batch.batchId,
                campaignId = campaign.campaignId,
                firstOrdinal = 0,
                manifestDigest = manifest("concurrent-generate-revision"),
                count = 1,
                expectedRevision = batch.revision,
                idempotencyKey = key("concurrent-generate-revision-a"),
            ),
            GenerateChunkCommand(
                tenantId = TENANT,
                batchId = batch.batchId,
                campaignId = campaign.campaignId,
                firstOrdinal = 0,
                manifestDigest = manifest("concurrent-generate-revision"),
                count = 2,
                expectedRevision = batch.revision,
                idempotencyKey = key("concurrent-generate-revision-b"),
            ),
        )

        val outcomes = executeConcurrently(commands) {
            service.generateChunk(it).applied()
        }

        outcomes.filterIsInstance<BatchSnapshot>() shouldHaveSize 1
        outcomes.count { it == BatchCommandFailure.STALE_REVISION } shouldBeEqualTo 1

        val stored = batchRow(batch.batchId)
        log.debug { "stored: $stored" }
        stored.revision shouldBeEqualTo batch.revision + 1
        stored.nextSourceOrdinal shouldBeEqualTo stored.acceptedCount
        entryCount(batch.batchId) shouldBeEqualTo stored.acceptedCount
    }

    @Test
    fun `same key conflict and active owner prevent a second campaign effect`() {
        val command = createCampaignCommand("idempotency-conflict")
        service.createCampaign(command).applied()

        assertFailsWith<BatchCommandException> {
            service.createCampaign(command.copy(endsAt = command.endsAt + 1.seconds()))
        }.reason shouldBeEqualTo BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT

        campaignCount(command.campaignId) shouldBeEqualTo 1

        val inProgress = createCampaignCommand("idempotency-progress")
        val idempotency = JdbcVoucherPoolIdempotencyRepository(digests)
        val decision = transactionExecutor().operatorTransaction {
            idempotency.acquire(
                scope = CommandScope(TENANT, "campaign-create"),
                rawKey = inProgress.idempotencyKey,
                fingerprint = createCampaignFingerprint(inProgress),
            )
        }
        decision.shouldBeInstanceOf<IdempotencyDecision.Execute>()

        assertFailsWith<BatchCommandException> {
            service.createCampaign(inProgress)
        }.reason shouldBeEqualTo BatchCommandFailure.COMMAND_IN_PROGRESS

        campaignCount(inProgress.campaignId) shouldBeEqualTo 0
    }

    @Test
    fun `business effect rolls back when idempotency finalize fails and same key can retry`() {
        val command = createCampaignCommand("finalize-rollback")
        installFinalizeFailureTrigger()

        assertFailsWith<RuntimeException> {
            service.createCampaign(command)
        }

        dropFinalizeFailureTrigger()
        campaignCount(command.campaignId) shouldBeEqualTo 0
        service.createCampaign(command).applied().campaignId shouldBeEqualTo command.campaignId
        campaignCount(command.campaignId) shouldBeEqualTo 1
    }

    @Test
    fun `policy updates are allowed only in draft and paused states`() {
        val draft = service.createCampaign(createCampaignCommand("policy-states")).applied()

        val updated = service.updatePolicy(
            UpdateCampaignPolicyCommand(
                tenantId = TENANT,
                campaignId = draft.campaignId,
                expectedRevision = draft.revision,
                policy = VoucherPoolPolicy(3, 1.minutes, 5.minutes, 1),
                idempotencyKey = key("policy-draft"),
            ),
        ).applied()

        val active = service.activateCampaign(
            CampaignRevisionCommand(
                tenantId = TENANT,
                campaignId = updated.campaignId,
                expectedRevision = updated.revision,
                idempotencyKey = key("policy-activate")
            ),
        ).applied()

        assertFailsWith<BatchCommandException> {
            service.updatePolicy(
                UpdateCampaignPolicyCommand(
                    tenantId = TENANT,
                    campaignId = active.campaignId,
                    expectedRevision = active.revision,
                    policy = VoucherPoolPolicy(4, 1.minutes, 5.minutes, 1),
                    idempotencyKey = key("policy-active"),
                ),
            )
        }.reason shouldBeEqualTo BatchCommandFailure.INVALID_STATE

        execute("UPDATE voucher_pool_campaigns SET state='PAUSED',revision=revision+1 WHERE campaign_id='${active.campaignId}'")
        val pausedRevision = campaignRevision(active.campaignId)
        service.updatePolicy(
            UpdateCampaignPolicyCommand(
                tenantId = TENANT,
                campaignId = active.campaignId,
                expectedRevision = pausedRevision,
                policy = VoucherPoolPolicy(5, 1.minutes, 5.minutes, 1),
                idempotencyKey = key("policy-paused"),
            ),
        ).applied().policyVersion shouldBeEqualTo 3
    }

    @Test
    fun `activation rejects a tampered persisted canonical checkpoint`() {
        val fixture = campaignAndBatch("tampered-checkpoint", expected = 2, initial = codes(20, 2))

        execute(
            "UPDATE voucher_pool_batches SET checkpoint_digest=decode(repeat('00',32),'hex') " +
                    "WHERE tenant_id='$TENANT' AND batch_id='${fixture.batch.batchId}'",
        )
        assertFailsWith<BatchCommandException> {
            service.activateBatch(
                BatchRevisionCommand(
                    tenantId = TENANT,
                    campaignId = fixture.campaign.campaignId,
                    batchId = fixture.batch.batchId,
                    expectedRevision = fixture.batch.revision,
                    idempotencyKey = key("activate-tampered-checkpoint"),
                ),
            )
        }.reason shouldBeEqualTo BatchCommandFailure.ACTIVATION_INCOMPLETE
    }

    @Test
    fun `validation failure records only bounded evidence and blocks activation`() {
        val fixture = campaignAndBatch("invalid", expected = 3, initial = listOf("VALID-0"))
        val before = batchRow(fixture.batch.batchId)
        val command = import(fixture, 1, listOf("VALID-1", "bad\ncode"))

        val rejection = assertFailsWith<PreparedChunkRejectedException> {
            service.importChunk(command)
        }
        val failed = batchRow(fixture.batch.batchId)

        log.debug { "failed: $failed" }
        failed.state shouldBeEqualTo BatchState.FAILED_TERMINAL
        failed.nextSourceOrdinal shouldBeEqualTo 3
        failed.acceptedCount shouldBeEqualTo before.acceptedCount
        failed.rejectedCount shouldBeEqualTo 2
        failed.checkpointDigest shouldBeEqualTo before.checkpointDigest
        entryCount(fixture.batch.batchId) shouldBeEqualTo 1
        rejection.evidenceCode shouldBeEqualTo failed.lastFailureCode
        rejection.evidenceCode shouldNotContain "bad"

        val plainOracle = MessageDigest.getInstance("SHA-256")
            .digest("bad\ncode".toUtf8Bytes())
            .take(8)
            .toByteArray()
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

        failed.lastFailureCode shouldNotContain plainOracle
        failed.lastFailureCode.orEmpty().length.let { it <= 64 }.shouldBeTrue()

        val replay = service.importChunk(command).shouldBeInstanceOf<MutationResult.Replay>()
        replay.descriptor.terminalCode shouldBeEqualTo VoucherPoolErrorCode.BATCH_FAILED_TERMINAL

        assertFailsWith<BatchCommandException> {
            service.activateBatch(
                BatchRevisionCommand(
                    tenantId = TENANT,
                    campaignId = fixture.campaign.campaignId,
                    batchId = fixture.batch.batchId,
                    expectedRevision = failed.revision,
                    idempotencyKey = key("activate-invalid")
                )
            )
        }.reason shouldBeEqualTo BatchCommandFailure.ACTIVATION_INCOMPLETE
    }

    @Test
    fun `initial mixed rejection creates only a terminal batch and terminal replay`() {
        val campaign = createCampaign("initial-invalid")
        val command = createBatchCommand(campaign, "initial-invalid", 2, listOf("INITIAL-VALID", "bad\ninitial"))

        val rejection = assertFailsWith<PreparedChunkRejectedException> {
            service.createImportBatch(command)
        }
        val failed = batchRow(command.batchId)
        log.debug { "failed: $failed" }
        failed.state shouldBeEqualTo BatchState.FAILED_TERMINAL
        failed.nextSourceOrdinal shouldBeEqualTo 2
        failed.acceptedCount shouldBeEqualTo 0
        failed.rejectedCount shouldBeEqualTo 2
        failed.checkpointDigest.shouldBeNull()
        failed.lastFailureCode shouldBeEqualTo rejection.evidenceCode

        entryCount(command.batchId) shouldBeEqualTo 0
        val replay = service.createImportBatch(command).shouldBeInstanceOf<MutationResult.Replay>()
        replay.descriptor.terminalCode shouldBeEqualTo VoucherPoolErrorCode.BATCH_FAILED_TERMINAL

        execute(
            "UPDATE voucher_pool_http_idempotency SET expires_at=statement_timestamp()-interval '1 second' " +
                    "WHERE tenant_id='$TENANT' AND operation='batch-create'",
        )
        transactionExecutor().operatorTransaction {
            JdbcVoucherPoolIdempotencyRepository(digests).purgeDescriptors(10)

        }
        val expired = service.createImportBatch(command).shouldBeInstanceOf<MutationResult.Expired>()
        log.debug { "expired: $expired" }
        expired.effectId.shouldBeNull()
        expired.terminalCode shouldBeEqualTo VoucherPoolErrorCode.BATCH_FAILED_TERMINAL
    }

    @Test
    fun `crypto rejection terminalizes atomically with bounded evidence replay and expiry`() {
        val fixture = campaignAndBatch("crypto-rejection", expected = 2, initial = listOf("CRYPTO-VALID"))
        val before = batchRow(fixture.batch.batchId)
        service = newService(envelopeCrypto = RejectingCrypto(crypto))

        val rawSecret = "CRYPTO-REJECTED-SECRET"
        val command = ImportChunkCommand(
            tenantId = TENANT,
            batchId = fixture.batch.batchId,
            campaignId = fixture.campaign.campaignId,
            firstOrdinal = 1,
            manifestDigest = manifest("crypto-rejection"),
            codes = listOf(rawSecret),
            expectedRevision = before.revision,
            idempotencyKey = key("crypto-rejection-chunk"),
        )

        val rejection = assertFailsWith<PreparedChunkRejectedException> {
            service.importChunk(command)
        }
        val failed = batchRow(fixture.batch.batchId)

        log.debug { "failed: $failed" }
        failed.state shouldBeEqualTo BatchState.FAILED_TERMINAL
        failed.nextSourceOrdinal shouldBeEqualTo 2
        failed.acceptedCount shouldBeEqualTo before.acceptedCount
        failed.rejectedCount shouldBeEqualTo 1
        failed.checkpointDigest shouldBeEqualTo before.checkpointDigest
        entryCount(fixture.batch.batchId) shouldBeEqualTo 1
        dedupCount(fixture.batch.batchId) shouldBeEqualTo 1
        failed.lastFailureCode shouldBeEqualTo rejection.evidenceCode
        failed.lastFailureCode.orEmpty() shouldNotContain rawSecret

        val plainOracle = MessageDigest.getInstance("SHA-256")
            .digest(rawSecret.toUtf8Bytes())
            .take(8).toByteArray().joinToString("") { "%02x".format(Locale.ROOT, it) }

        failed.lastFailureCode shouldNotContain plainOracle
        failed.lastFailureCode.orEmpty().length shouldBeLessOrEqualTo 64

        val replay = service.importChunk(command) as MutationResult.Replay
        replay.descriptor.terminalCode shouldBeEqualTo VoucherPoolErrorCode.BATCH_FAILED_TERMINAL
        execute(
            "UPDATE voucher_pool_http_idempotency SET expires_at=statement_timestamp()-interval '1 second' " +
                    "WHERE tenant_id='$TENANT' AND operation='batch-import-chunk'",
        )
        transactionExecutor().operatorTransaction {
            JdbcVoucherPoolIdempotencyRepository(digests).purgeDescriptors(10)
        }

        val expired = service.importChunk(command).shouldBeInstanceOf<MutationResult.Expired>()
        log.debug { "expired: $expired" }
        expired.effectId.shouldBeNull()
        expired.terminalCode shouldBeEqualTo VoucherPoolErrorCode.BATCH_FAILED_TERMINAL
    }

    @Test
    fun `transaction crash rolls back a chunk and exact ordinal resumes`() {
        val fixture = campaignAndBatch("crash", expected = 4, initial = codes(0, 2))
        installOrdinalFailureTrigger(3)

        assertFailsWith<RuntimeException> {
            service.importChunk(import(fixture, 2, codes(2, 2)))
        }
        dropOrdinalFailureTrigger()

        batchRow(fixture.batch.batchId).nextSourceOrdinal shouldBeEqualTo 2
        entryCount(fixture.batch.batchId) shouldBeEqualTo 2

        service.importChunk(
            import(fixture, 2, codes(2, 2))
        ).applied().nextSourceOrdinal shouldBeEqualTo 4
    }

    @Test
    fun `tenant lifetime digest rejects duplicate codes across campaigns`() {
        val first = campaignAndBatch("duplicate-a", expected = 1, initial = listOf("GLOBAL-CODE"))
        first.batch.acceptedCount shouldBeEqualTo 1

        val campaign = createCampaign("duplicate-b")
        val batch = createBatch(campaign, "duplicate-b", expected = 2, initial = listOf("UNIQUE-CODE"))

        val failure = assertFailsWith<BatchCommandException> {
            service.importChunk(
                ImportChunkCommand(
                    tenantId = TENANT,
                    batchId = batch.batchId,
                    campaignId = campaign.campaignId,
                    firstOrdinal = 1,
                    manifestDigest = manifest("duplicate-b"),
                    codes = listOf("GLOBAL-CODE"),
                    expectedRevision = batch.revision,
                    idempotencyKey = key("duplicate-code"),
                ),
            )
        }
        failure.reason shouldBeEqualTo BatchCommandFailure.DUPLICATE_CODE
        batchRow(batch.batchId).state shouldBeEqualTo BatchState.FAILED_TERMINAL
        entryCount(batch.batchId) shouldBeEqualTo 1
    }

    @Test
    fun `duplicate create with a new key cannot mutate the existing batch`() {
        val campaign = createCampaign("create-replay-authority")
        val original = createBatchCommand(
            campaign = campaign,
            suffix = "create-replay-authority",
            expected = 2,
            initial = listOf("CREATE-0")
        ).withActivatesAt(POSTGRES_ROUNDING_INSTANT)

        val created = service.createImportBatch(original).applied()
        val exactReplay = CreateImportBatchCommand(
            tenantId = original.tenantId,
            batchId = original.batchId,
            campaignId = original.campaignId,
            sourceKind = original.sourceKind,
            manifestDigest = original.manifestDigest,
            requestFingerprint = original.requestFingerprint,
            expectedCount = original.expectedCount,
            activatesAt = original.activatesAt,
            initialCodes = original.initialCodes,
            idempotencyKey = key("create-replay-exact-new-key"),
        )
        service.createImportBatch(exactReplay).applied() shouldBeEqualTo created
        val conflicting = CreateImportBatchCommand(
            tenantId = original.tenantId,
            batchId = original.batchId,
            campaignId = original.campaignId,
            sourceKind = original.sourceKind,
            manifestDigest = original.manifestDigest,
            requestFingerprint = DigestValue.of(ByteArray(32) { 99 }),
            expectedCount = 3,
            activatesAt = original.activatesAt,
            initialCodes = listOf("CREATE-CHANGED"),
            idempotencyKey = key("create-replay-new-key"),
        )

        assertFailsWith<BatchCommandException> {
            service.createImportBatch(conflicting)
        }.reason shouldBeEqualTo BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT

        batchRow(created.batchId) shouldBeEqualTo created
        entryCount(created.batchId) shouldBeEqualTo 1
    }

    @Test
    fun `concurrent exact campaign and batch creates with different keys converge on one effect`() {
        service = newService(repository = BarrierCreateRepository(JdbcVoucherPoolRepository()))
        val campaignCommand = createCampaignCommand("concurrent-create")
            .copy(
                startsAt = POSTGRES_ROUNDING_INSTANT,
                endsAt = POSTGRES_ROUNDING_INSTANT.plusSeconds(3_600),
            )
        val secondCampaignCommand = campaignCommand.copy(idempotencyKey = key("concurrent-create-campaign-second"))
        val pool = Executors.newFixedThreadPool(2)

        try {
            val campaigns: List<CampaignSnapshot> = listOf(campaignCommand, secondCampaignCommand)
                .map { command ->
                    pool.submit<CampaignSnapshot> { service.createCampaign(command).applied() }
                }
                .map { it.get(15.seconds) }

            campaigns.distinct() shouldHaveSize 1
            campaignCount(campaignCommand.campaignId) shouldBeEqualTo 1

            val draft = campaigns.first()
            val campaign = service.activateCampaign(
                CampaignRevisionCommand(
                    tenantId = TENANT,
                    campaignId = draft.campaignId,
                    expectedRevision = draft.revision,
                    idempotencyKey = key("concurrent-create-activate")
                ),
            ).applied()

            val firstBatch = createBatchCommand(
                campaign = campaign,
                suffix = "concurrent-create",
                expected = 1,
                initial = listOf("CONCURRENT-0")
            ).withActivatesAt(POSTGRES_ROUNDING_INSTANT + 60.seconds())

            val secondBatch = CreateImportBatchCommand(
                tenantId = firstBatch.tenantId,
                batchId = firstBatch.batchId,
                campaignId = firstBatch.campaignId,
                sourceKind = firstBatch.sourceKind,
                manifestDigest = firstBatch.manifestDigest,
                requestFingerprint = firstBatch.requestFingerprint,
                expectedCount = firstBatch.expectedCount,
                activatesAt = firstBatch.activatesAt,
                expiresAt = firstBatch.expiresAt,
                initialCodes = firstBatch.initialCodes,
                idempotencyKey = key("concurrent-create-batch-second"),
            )
            val batches: List<BatchSnapshot> = listOf(firstBatch, secondBatch)
                .map { command ->
                    pool.submit<BatchSnapshot> { service.createImportBatch(command).applied() }
                }
                .map { it.get(15.seconds) }

            batches.distinct() shouldHaveSize 1
            entryCount(firstBatch.batchId) shouldBeEqualTo 1
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `failed create terminal recovery cannot corrupt a different authority winner`() {
        val campaign = createCampaign("terminal-authority-race")
        val failingCommand = createBatchCommand(
            campaign = campaign,
            suffix = "terminal-authority-race",
            expected = 1,
            initial = listOf("bad\ncreate"),
        )
        val raceRepository = TerminalRecoveryRaceRepository(JdbcVoucherPoolRepository())
        val failingService = newService(repository = raceRepository)
        val pool = Executors.newSingleThreadExecutor()

        try {
            val failingFuture = pool.submit<MutationResult<BatchSnapshot>> {
                Thread.currentThread().name = TERMINAL_RACE_THREAD
                failingService.createImportBatch(failingCommand)
            }
            raceRepository.awaitTerminalRecovery().shouldBeTrue()

            val healthyCommand = CreateImportBatchCommand(
                tenantId = TENANT,
                batchId = failingCommand.batchId,
                campaignId = campaign.campaignId,
                sourceKind = BatchSourceKind.IMPORTED,
                manifestDigest = manifest("terminal-authority-race-winner"),
                requestFingerprint = DigestValue.of(ByteArray(32) { 44 }),
                expectedCount = 2,
                activatesAt = failingCommand.activatesAt + 1.seconds(),
                expiresAt = failingCommand.activatesAt + 600.seconds(),
                initialCodes = listOf("HEALTHY-WINNER"),
                idempotencyKey = key("terminal-authority-race-winner"),
            )
            val healthy = service.createImportBatch(healthyCommand).applied()
            raceRepository.releaseTerminalRecovery()

            val wrapped = assertFailsWith<ExecutionException> {
                failingFuture.get(15.seconds)
            }
            wrapped.cause.shouldBeInstanceOf<PreparedChunkRejectedException>()
            batchRow(healthy.batchId) shouldBeEqualTo healthy
            healthy.state shouldBeEqualTo BatchState.STAGING
            healthy.nextSourceOrdinal shouldBeEqualTo 1
            healthy.acceptedCount shouldBeEqualTo 1
            healthy.rejectedCount shouldBeEqualTo 0
            entryCount(healthy.batchId) shouldBeEqualTo 1

            val replay = failingService.createImportBatch(failingCommand).shouldBeInstanceOf<MutationResult.Replay>()
            replay.descriptor.terminalCode shouldBeEqualTo VoucherPoolErrorCode.BATCH_FAILED_TERMINAL
        } finally {
            raceRepository.releaseTerminalRecovery()
            pool.shutdownNow()
        }
    }

    @Test
    fun `named nonce collision is retryable while unknown integrity authority fails closed`() {
        service = newService(envelopeCrypto = FixedNonceCrypto(crypto))
        val fixture = campaignAndBatch("nonce-collision", expected = 2, initial = listOf("NONCE-0"))

        assertFailsWith<BatchCommandException> {
            service.importChunk(
                ImportChunkCommand(
                    tenantId = TENANT,
                    batchId = fixture.batch.batchId,
                    campaignId = fixture.campaign.campaignId,
                    firstOrdinal = 1,
                    manifestDigest = manifest("nonce-collision"),
                    codes = listOf("NONCE-1"),
                    expectedRevision = fixture.batch.revision,
                    idempotencyKey = key("nonce-collision-chunk"),
                ),
            )
        }.reason shouldBeEqualTo BatchCommandFailure.RETRYABLE_INTEGRITY_COLLISION

        batchRow(fixture.batch.batchId) shouldBeEqualTo fixture.batch
        entryCount(fixture.batch.batchId) shouldBeEqualTo 1

        service = newService()
        val unknown = campaignAndBatch("unknown-integrity", expected = 2, initial = listOf("UNKNOWN-0"))
        installUnknownIntegrityTrigger(1)

        val unknownCommand = ImportChunkCommand(
            tenantId = TENANT,
            batchId = unknown.batch.batchId,
            campaignId = unknown.campaign.campaignId,
            firstOrdinal = 1,
            manifestDigest = manifest("unknown-integrity"),
            codes = listOf("UNKNOWN-1"),
            expectedRevision = unknown.batch.revision,
            idempotencyKey = key("unknown-integrity-chunk"),
        )
        val failure = assertFailsWith<RuntimeException> {
            service.importChunk(unknownCommand)
        }
        dropUnknownIntegrityTrigger()

        log.debug { "failure: $failure" }
        failure.shouldNotBeInstanceOf<BatchCommandException>()

        batchRow(unknown.batch.batchId) shouldBeEqualTo unknown.batch
        entryCount(unknown.batch.batchId) shouldBeEqualTo 1

        assertFailsWith<BatchCommandException> {
            service.importChunk(unknownCommand)
        }.reason shouldBeEqualTo BatchCommandFailure.COMMAND_IN_PROGRESS
    }

    @Test
    fun `generation rollback regenerates codes without persisting a seed`() {
        val generated = ArrayDeque(listOf("GEN-A", "GEN-B", "GEN-C", "GEN-D"))
        val source = object: GeneratedVoucherCodeSource {
            override val deterministic: Boolean = true
            override fun nextCode(): String = generated.removeFirst()
        }
        service = newService(source, VoucherPoolRuntimeProfile.LOOPBACK_TEST)
        val campaign = createCampaign("generated")
        val batch = createBatch(
            campaign = campaign,
            suffix = "generated",
            expected = 2,
            initial = emptyList(),
            sourceKind = BatchSourceKind.GENERATED,
        )
        installOrdinalFailureTrigger(1)

        assertFailsWith<RuntimeException> {
            service.generateChunk(
                GenerateChunkCommand(
                    tenantId = TENANT,
                    batchId = batch.batchId,
                    campaignId = campaign.campaignId,
                    firstOrdinal = 0,
                    manifestDigest = manifest("generated"),
                    count = 2,
                    expectedRevision = batch.revision,
                    idempotencyKey = key("generate-0"),
                ),
            )
        }
        dropOrdinalFailureTrigger()

        service.generateChunk(
            GenerateChunkCommand(
                tenantId = TENANT,
                batchId = batch.batchId,
                campaignId = campaign.campaignId,
                firstOrdinal = 0,
                manifestDigest = manifest("generated"),
                count = 2,
                expectedRevision = batch.revision,
                idempotencyKey = key("generate-0"),
            ),
        ).applied()
        entryCount(batch.batchId) shouldBeEqualTo 2
        rawStorageContains("GEN-A").shouldBeFalse()
        rawStorageContains("GEN-C").shouldBeFalse()
        rawStorageContains("seed").shouldBeFalse()
    }

    @Test
    fun `activation requires exact count no gap and no unresolved failure`() {
        val fixture = campaignAndBatch("activation", expected = 3, initial = codes(0, 2))
        assertFailsWith<BatchCommandException> {
            service.activateBatch(
                BatchRevisionCommand(
                    tenantId = TENANT,
                    campaignId = fixture.campaign.campaignId,
                    batchId = fixture.batch.batchId,
                    expectedRevision = fixture.batch.revision,
                    idempotencyKey = key("activate-incomplete")
                )
            )
        }.reason shouldBeEqualTo BatchCommandFailure.ACTIVATION_INCOMPLETE

        val completed = service.importChunk(import(fixture, 2, codes(2, 1))).applied()
        log.debug { "completed: $completed" }

        val active = service.activateBatch(
            BatchRevisionCommand(
                tenantId = TENANT,
                campaignId = fixture.campaign.campaignId,
                batchId = fixture.batch.batchId,
                expectedRevision = completed.revision,
                idempotencyKey = key("activate-complete")
            ),
        ).applied()
        log.debug { "active=$active" }
        active.state shouldBeEqualTo BatchState.ACTIVE

        val gap = campaignAndBatch("gap", expected = 3, initial = codes(10, 3))
        execute("DELETE FROM voucher_pool_entries WHERE tenant_id='$TENANT' AND batch_id='${gap.batch.batchId}' AND source_ordinal=1")

        assertFailsWith<BatchCommandException> {
            service.activateBatch(
                BatchRevisionCommand(
                    tenantId = TENANT,
                    campaignId = gap.campaign.campaignId,
                    batchId = gap.batch.batchId,
                    expectedRevision = gap.batch.revision,
                    idempotencyKey = key("activate-gap")
                )
            )
        }.reason shouldBeEqualTo BatchCommandFailure.ACTIVATION_INCOMPLETE
    }

    @Test
    fun `ten thousand entry batch finalizes through bounded chunks`() {
        val tracking = TrackingVoucherPoolRepository(JdbcVoucherPoolRepository())
        service = newService(repository = tracking)
        val fixture = campaignAndBatch("ten-thousand", expected = 10_000, initial = codes(0, 500))
        var snapshot = fixture.batch

        for (start in 500 until 10_000 step 500) {
            snapshot =
                service.importChunk(
                    import(
                        fixture = fixture,
                        firstOrdinal = start.toLong(),
                        codes = codes(start, 500),
                        expectedRevision = snapshot.revision
                    )
                ).applied()
        }

        snapshot.nextSourceOrdinal shouldBeEqualTo 10_000
        snapshot.acceptedCount shouldBeEqualTo 10_000
        entryCount(snapshot.batchId) shouldBeEqualTo 10_000

        service.activateBatch(
            BatchRevisionCommand(
                tenantId = TENANT,
                campaignId = fixture.campaign.campaignId,
                batchId = snapshot.batchId,
                expectedRevision = snapshot.revision,
                idempotencyKey = key("activate-ten-thousand")
            ),
        ).applied().state shouldBeEqualTo BatchState.ACTIVE

        log.debug { "tracking=$tracking" }
        tracking.maximumRequestedCount shouldBeEqualTo 500
        tracking.requestCount shouldBeEqualTo 20
    }

    private fun campaignAndBatch(
        suffix: String,
        expected: Long,
        initial: List<String>,
    ): Fixture {
        val campaign = createCampaign(suffix)
        return Fixture(campaign, createBatch(campaign, suffix, expected, initial))
    }

    private fun createCampaign(suffix: String): CampaignSnapshot {
        val campaign = service.createCampaign(createCampaignCommand(suffix)).applied()

        return service.activateCampaign(
            CampaignRevisionCommand(
                tenantId = TENANT,
                campaignId = campaign.campaignId,
                expectedRevision = campaign.revision,
                idempotencyKey = key("activate-campaign-$suffix")
            ),
        ).applied()
    }

    private fun createCampaignCommand(suffix: String): CreateCampaignCommand =
        CreateCampaignCommand(
            tenantId = TENANT,
            campaignId = UUID.nameUUIDFromBytes("campaign-$suffix".toUtf8Bytes()),
            startsAt = Instant.now().minusSeconds(60),
            endsAt = Instant.now().plusSeconds(3_600),
            policy = VoucherPoolPolicy(2, 1.minutes, 5.minutes, 1),
            idempotencyKey = key("create-campaign-$suffix"),
        )

    private fun createBatch(
        campaign: CampaignSnapshot,
        suffix: String,
        expected: Long,
        initial: List<String>,
        sourceKind: BatchSourceKind = BatchSourceKind.IMPORTED,
    ): BatchSnapshot =
        service.createImportBatch(createBatchCommand(campaign, suffix, expected, initial, sourceKind)).applied()

    private fun createBatchCommand(
        campaign: CampaignSnapshot,
        suffix: String,
        expected: Long,
        initial: List<String>,
        sourceKind: BatchSourceKind = BatchSourceKind.IMPORTED,
    ) = CreateImportBatchCommand(
        tenantId = TENANT,
        batchId = UUID.nameUUIDFromBytes("batch-$suffix".toUtf8Bytes()),
        campaignId = campaign.campaignId,
        sourceKind = sourceKind,
        manifestDigest = manifest(suffix),
        requestFingerprint = DigestValue.of(ByteArray(32) { 3 }),
        expectedCount = expected,
        activatesAt = Instant.now(),
        initialCodes = initial,
        idempotencyKey = key("create-batch-$suffix"),
    )

    private fun CreateImportBatchCommand.withActivatesAt(activatesAt: Instant) =
        CreateImportBatchCommand(
            tenantId = tenantId,
            batchId = batchId,
            campaignId = campaignId,
            sourceKind = sourceKind,
            manifestDigest = manifestDigest,
            requestFingerprint = requestFingerprint,
            expectedCount = expectedCount,
            activatesAt = activatesAt,
            expiresAt = expiresAt,
            initialCodes = initialCodes,
            idempotencyKey = idempotencyKey,
        )

    private fun import(
        fixture: Fixture,
        firstOrdinal: Long,
        codes: List<String>,
        expectedRevision: Long = fixture.batch.revision,
    ) = ImportChunkCommand(
        TENANT,
        fixture.batch.batchId,
        fixture.campaign.campaignId,
        firstOrdinal,
        manifest(fixtureSuffix(fixture)),
        codes,
        expectedRevision,
        key("import-${fixtureSuffix(fixture)}-$firstOrdinal"),
    )

    private fun fixtureSuffix(fixture: Fixture): String = when (fixture.batch.batchId) {
        UUID.nameUUIDFromBytes("batch-resume".toUtf8Bytes())              -> "resume"
        UUID.nameUUIDFromBytes("batch-invalid".toUtf8Bytes())             -> "invalid"
        UUID.nameUUIDFromBytes("batch-crash".toUtf8Bytes())               -> "crash"
        UUID.nameUUIDFromBytes("batch-activation".toUtf8Bytes())          -> "activation"
        UUID.nameUUIDFromBytes("batch-ten-thousand".toUtf8Bytes())        -> "ten-thousand"
        UUID.nameUUIDFromBytes("batch-tampered-checkpoint".toUtf8Bytes()) -> "tampered-checkpoint"
        else                                                              -> error("unknown fixture")
    }

    private fun newService(
        source: GeneratedVoucherCodeSource = SecureRandomVoucherCodeSource(),
        runtimeProfile: VoucherPoolRuntimeProfile = VoucherPoolRuntimeProfile.PRODUCTION,
        envelopeCrypto: VoucherEnvelopeCrypto = crypto,
        repository: VoucherPoolRepository = JdbcVoucherPoolRepository(),
    ): JdbcCampaignBatchCommandService {
        val manager = SpringTransactionManager(dataSource, DatabaseConfig {}, false)

        return JdbcCampaignBatchCommandService(
            executor = VoucherPoolJdbcExecutor(DatabasePermitGate.default(32), manager),
            repository = repository,
            idempotency = JdbcVoucherPoolIdempotencyRepository(digests),
            digests = digests,
            crypto = envelopeCrypto,
            generatedCodes = source,
            runtimeProfile = runtimeProfile,
        )
    }

    private fun transactionExecutor(): VoucherPoolJdbcExecutor {
        val manager = SpringTransactionManager(dataSource, DatabaseConfig {}, false)
        return VoucherPoolJdbcExecutor(DatabasePermitGate.default(32), manager)
    }

    private fun digestService(): VoucherDigestService =
        VoucherDigestService(
            stableDedupKey = DigestKey.of(7, keyBytes(7)),
            commandTombstoneKey = DigestKey.of(4, keyBytes(4)),
            rotatingKeys = mapOf(
                DigestPurpose.VERIFICATION to DigestKeyRing.of(DigestKey.of(1, keyBytes(1))),
                DigestPurpose.USER_IDENTITY to DigestKeyRing.of(DigestKey.of(2, keyBytes(2))),
                DigestPurpose.REDIS_SIGNAL to DigestKeyRing.of(DigestKey.of(3, keyBytes(3))),
                DigestPurpose.AUDIT to DigestKeyRing.of(DigestKey.of(5, keyBytes(5))),
            ),
        )

    private fun manifest(suffix: String): DigestValue = DigestValue.of(
        MessageDigest.getInstance("SHA-256").digest("manifest-$suffix".toUtf8Bytes()),
    )

    private fun codes(offset: Int, count: Int): List<String> = List(count) { "CODE-${offset + it}" }

    private fun batchRow(batchId: UUID): BatchSnapshot = dataSource.connection.use { connection ->
        @Suppress("SqlResolve")
        connection.prepareStatement(
            "SELECT state,next_source_ordinal,expected_count,accepted_count,rejected_count,checkpoint_digest,last_failure_code,revision,campaign_id FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=?",
        ).use { statement ->
            statement.setString(1, TENANT)
            statement.setObject(2, batchId)

            statement.executeQuery().use { result ->
                result.next()
                BatchSnapshot(
                    tenantId = TENANT,
                    batchId = batchId,
                    campaignId = result.getObject("campaign_id", UUID::class.java),
                    state = BatchState.valueOf(result.getString("state")),
                    nextSourceOrdinal = result.getLong("next_source_ordinal"),
                    expectedCount = result.getLong("expected_count"),
                    acceptedCount = result.getLong("accepted_count"),
                    rejectedCount = result.getLong("rejected_count"),
                    checkpointDigest = result.getBytes("checkpoint_digest")?.let(DigestValue::of),
                    lastFailureCode = result.getString("last_failure_code"),
                    revision = result.getLong("revision"),
                )
            }
        }
    }

    private fun entryCount(batchId: UUID): Long = scalar(
        "SELECT count(*) FROM voucher_pool_entries WHERE tenant_id='$TENANT' AND batch_id='$batchId'",
    )

    private fun dedupCount(batchId: UUID): Long = scalar(
        "SELECT count(*) FROM voucher_pool_code_dedup WHERE tenant_id='$TENANT' AND first_batch_id='$batchId'",
    )

    private fun campaignCount(campaignId: UUID): Long = scalar(
        "SELECT count(*) FROM voucher_pool_campaigns WHERE tenant_id='$TENANT' AND campaign_id='$campaignId'",
    )

    private fun campaignRevision(campaignId: UUID): Long = scalar(
        "SELECT revision FROM voucher_pool_campaigns WHERE tenant_id='$TENANT' AND campaign_id='$campaignId'",
    )

    private fun rawStorageContains(value: String): Boolean = scalar(
        "SELECT count(*) FROM voucher_pool_entries WHERE position(convert_to('$value','UTF8') in code_ciphertext)>0",
    ) > 0

    private fun scalar(sql: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().executeQuery(sql).use { it.singleLong() }
    }

    private fun execute(sql: String) {
        dataSource.connection.use {
            it.createStatement().execute(sql)
        }
    }

    private fun installOrdinalFailureTrigger(ordinal: Long) {
        execute(
            """CREATE FUNCTION fail_ingest_ordinal() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.source_ordinal=$ordinal THEN RAISE EXCEPTION 'simulated crash' USING ERRCODE='40001'; END IF; RETURN NEW; END $$;
                CREATE TRIGGER fail_ingest_ordinal BEFORE INSERT ON voucher_pool_entries FOR EACH ROW EXECUTE FUNCTION fail_ingest_ordinal()""".trimIndent(),
        )
    }

    private fun dropOrdinalFailureTrigger() {
        execute("DROP TRIGGER IF EXISTS fail_ingest_ordinal ON voucher_pool_entries; DROP FUNCTION IF EXISTS fail_ingest_ordinal()")
    }

    private fun installFinalizeFailureTrigger() {
        execute(
            """CREATE FUNCTION fail_idempotency_finalize() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.status='COMPLETED' THEN RAISE EXCEPTION 'simulated finalize crash' USING ERRCODE='40001'; END IF; RETURN NEW; END $$;
                CREATE TRIGGER fail_idempotency_finalize BEFORE UPDATE ON voucher_pool_http_idempotency
                FOR EACH ROW EXECUTE FUNCTION fail_idempotency_finalize()""".trimIndent(),
        )
    }

    private fun dropFinalizeFailureTrigger() {
        execute(
            "DROP TRIGGER IF EXISTS fail_idempotency_finalize ON voucher_pool_http_idempotency; " +
                    "DROP FUNCTION IF EXISTS fail_idempotency_finalize()",
        )
    }

    @Suppress("SameParameterValue")
    private fun installUnknownIntegrityTrigger(ordinal: Long) {
        execute(
            """CREATE FUNCTION fail_unknown_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.source_ordinal=$ordinal THEN
                  RAISE EXCEPTION 'unknown integrity authority' USING ERRCODE='23505',CONSTRAINT='unknown_integrity_authority';
                END IF; RETURN NEW; END $$;
                CREATE TRIGGER fail_unknown_integrity BEFORE INSERT ON voucher_pool_entries
                FOR EACH ROW EXECUTE FUNCTION fail_unknown_integrity()""".trimIndent(),
        )
    }

    private fun dropUnknownIntegrityTrigger() {
        execute(
            "DROP TRIGGER IF EXISTS fail_unknown_integrity ON voucher_pool_entries; " +
                    "DROP FUNCTION IF EXISTS fail_unknown_integrity()",
        )
    }

    private fun createCampaignFingerprint(command: CreateCampaignCommand): CommandFingerprint =
        VoucherPoolFingerprint.command(
            "campaign-create",
            mapOf(
                "campaignId" to command.campaignId.toString(),
                "startsAt" to command.startsAt.toString(),
                "endsAt" to command.endsAt.toString(),
                "perUserLimit" to command.policy.perUserLimit.toString(),
                "reservationTtlSeconds" to command.policy.reservationTtl.inWholeSeconds.toString(),
                "allocationTtlSeconds" to command.policy.allocationTtl.inWholeSeconds.toString(),
                "replacementAllowance" to command.policy.replacementAllowance.toString(),
            ),
        )

    private fun keyBytes(seed: Int) = ByteArray(32) { (seed + it).toByte() }

    private fun key(suffix: String): String = "voucher-ingest-$suffix"

    private fun <T> MutationResult<T>.applied(): T = (this as MutationResult.Applied<T>).value

    private fun MutationResult<*>.shouldReplay(effectId: UUID, revision: Long, outcome: String) {
        val replay = this as MutationResult.Replay
        log.debug { "replay: $replay" }
        replay.descriptor.effectId shouldBeEqualTo effectId
        replay.descriptor.revision shouldBeEqualTo revision
        replay.descriptor.outcome shouldBeEqualTo outcome
    }

    private inline fun <T> executeConcurrently(
        commands: List<T>,
        crossinline action: (T) -> BatchSnapshot,
    ): List<Any> {
        val pool = Executors.newFixedThreadPool(commands.size)
        val start = CyclicBarrier(commands.size)

        return try {
            commands.map { command ->
                pool.submit<Any> {
                    start.await(10.seconds)
                    try {
                        action(command)
                    } catch (failure: BatchCommandException) {
                        failure.reason
                    }
                }
            }.map { it.get(15.seconds) }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(3.seconds)
        }
    }

    private fun adminConnection(): Connection =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )

    private data class Fixture(
        val campaign: CampaignSnapshot,
        val batch: BatchSnapshot,
    )

    private class BoundaryCheckingCrypto(private val delegate: VoucherEnvelopeCrypto): VoucherEnvelopeCrypto {
        override fun encrypt(entryIdentity: EntryIdentity, code: CanonicalVoucherCode): EncryptedVoucherCode {
            TransactionManager.currentOrNull().shouldBeNull()
            return delegate.encrypt(entryIdentity, code)
        }

        override fun decryptAndVerify(
            entryIdentity: EntryIdentity,
            encrypted: EncryptedVoucherCode,
            expectedStableDedup: VoucherDigest,
        ): CanonicalVoucherCode = delegate.decryptAndVerify(entryIdentity, encrypted, expectedStableDedup)
    }

    private class FixedNonceCrypto(private val delegate: VoucherEnvelopeCrypto): VoucherEnvelopeCrypto {
        override fun encrypt(
            entryIdentity: EntryIdentity,
            code: CanonicalVoucherCode,
        ): EncryptedVoucherCode =
            delegate.encrypt(entryIdentity, code).copy(
                codeNonce = ByteArray(12) { 11 },
                wrapNonce = ByteArray(12) { 12 },
            )

        override fun decryptAndVerify(
            entryIdentity: EntryIdentity,
            encrypted: EncryptedVoucherCode,
            expectedStableDedup: VoucherDigest,
        ): CanonicalVoucherCode =
            delegate.decryptAndVerify(entryIdentity, encrypted, expectedStableDedup)
    }

    private class RejectingCrypto(private val delegate: VoucherEnvelopeCrypto): VoucherEnvelopeCrypto {
        override fun encrypt(entryIdentity: EntryIdentity, code: CanonicalVoucherCode): EncryptedVoucherCode =
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_CIPHERTEXT)

        override fun decryptAndVerify(
            entryIdentity: EntryIdentity,
            encrypted: EncryptedVoucherCode,
            expectedStableDedup: VoucherDigest,
        ): CanonicalVoucherCode =
            delegate.decryptAndVerify(entryIdentity, encrypted, expectedStableDedup)
    }

    private class TrackingVoucherPoolRepository(
        private val delegate: VoucherPoolRepository,
    ): VoucherPoolRepository by delegate {
        var maximumRequestedCount: Int = 0
            private set
        var requestCount: Int = 0
            private set

        override fun committedOrdinalDigests(
            tenantId: String,
            batchId: UUID,
            firstOrdinal: Long,
            count: Int,
        ): List<CommittedOrdinalDigest> {
            requestCount++
            maximumRequestedCount = maxOf(maximumRequestedCount, count)
            return delegate.committedOrdinalDigests(tenantId, batchId, firstOrdinal, count)
        }
    }

    private class BarrierCreateRepository(
        private val delegate: VoucherPoolRepository,
    ): VoucherPoolRepository by delegate {
        private val campaignBarrier = CyclicBarrier(2)
        private val batchBarrier = CyclicBarrier(2)

        override fun createCampaign(campaign: CampaignRecord): CampaignRecord {
            campaignBarrier.await(10.seconds)
            return delegate.createCampaign(campaign)
        }

        override fun createBatch(batch: BatchRecord): BatchRecord {
            batchBarrier.await(10.seconds)
            return delegate.createBatch(batch)
        }
    }

    private class TerminalRecoveryRaceRepository(
        private val delegate: VoucherPoolRepository,
    ): VoucherPoolRepository by delegate {
        private val terminalRecoveryStarted = CountDownLatch(1)
        private val winnerCommitted = CountDownLatch(1)
        private val campaignLockCalls = ThreadLocal.withInitial { 0 }

        override fun lockCampaignForShare(tenantId: String, campaignId: UUID): CampaignRecord {
            if (Thread.currentThread().name == TERMINAL_RACE_THREAD) {
                val call = campaignLockCalls.get() + 1
                campaignLockCalls.set(call)
                if (call == 2) {
                    terminalRecoveryStarted.countDown()
                    winnerCommitted.await(10.seconds).shouldBeTrue() // "different-authority winner did not commit"
                }
            }
            return delegate.lockCampaignForShare(tenantId, campaignId)
        }

        fun awaitTerminalRecovery(): Boolean = terminalRecoveryStarted.await(10.seconds)

        fun releaseTerminalRecovery() = winnerCommitted.countDown()
    }
}
