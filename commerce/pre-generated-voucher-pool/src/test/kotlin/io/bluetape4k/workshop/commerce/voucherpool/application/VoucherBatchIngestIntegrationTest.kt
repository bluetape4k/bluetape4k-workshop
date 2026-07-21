@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.JdbcVoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.CommittedOrdinalDigest
import io.bluetape4k.workshop.commerce.voucherpool.persistence.BatchRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcVoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.security.AesGcmVoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKey
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKeyRing
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.EncryptedVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.security.EntryIdentity
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigest
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoException
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoFailureReason
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKek
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.io.ClassPathResource
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CyclicBarrier
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Suppress("LargeClass")
internal class VoucherBatchIngestIntegrationTest {
    private val schema = "voucher_ingest_${Base58.randomString(8).lowercase()}"
    private val dataSource = PGSimpleDataSource().apply {
        setURL(postgres.jdbcUrl)
        user = postgres.username ?: PostgreSQLServer.USERNAME
        password = postgres.password ?: PostgreSQLServer.PASSWORD
        currentSchema = schema
    }
    private lateinit var digests: VoucherDigestService
    private lateinit var service: JdbcCampaignBatchCommandService
    private lateinit var crypto: VoucherEnvelopeCrypto

    @BeforeAll
    fun migrate() {
        adminConnection().use { it.createStatement().execute("CREATE SCHEMA $schema") }
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
        adminConnection().use { it.createStatement().execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    @BeforeEach
    fun reset() {
        dataSource.connection.use {
            it.createStatement().execute(
                "TRUNCATE voucher_pool_http_idempotency,voucher_pool_command_tombstones,voucher_pool_entries,voucher_pool_code_dedup,voucher_pool_batches,voucher_pool_campaigns CASCADE",
            )
        }
        service = newService()
    }

    @Test
    fun `import checkpoint commits ordinal digest and counts then resumes after restart`() {
        val fixture = campaignAndBatch("resume", expected = 6, initial = codes(0, 2))
        val first = fixture.batch
        first.nextSourceOrdinal shouldBeEqualTo 2
        first.acceptedCount shouldBeEqualTo 2
        first.rejectedCount shouldBeEqualTo 0

        service = newService()
        val second = service.importChunk(import(fixture, 2, codes(2, 2))).applied()
        second.nextSourceOrdinal shouldBeEqualTo 4
        second.acceptedCount shouldBeEqualTo 4
        (second.checkpointDigest == first.checkpointDigest).shouldBeFalse()

        service.importChunk(import(fixture, 0, codes(0, 2), second.revision)).applied().nextSourceOrdinal shouldBeEqualTo 4
        assertFailsWith<BatchCommandException> {
            service.importChunk(import(fixture, 0, listOf("CHANGED-0", "CHANGED-1"), second.revision))
        }.reason shouldBeEqualTo BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT
        assertFailsWith<BatchCommandException> {
            service.importChunk(
                ImportChunkCommand(
                    TENANT,
                    fixture.batch.batchId,
                    fixture.campaign.campaignId,
                    0,
                    manifest("resume"),
                    listOf("CHANGED-0", "CHANGED-1"),
                    second.revision,
                    key("changed-committed-chunk"),
                ),
            )
        }.reason shouldBeEqualTo BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT
        entryCount(fixture.batch.batchId) shouldBeEqualTo 4
    }

    @Test
    fun `every campaign and batch mutation replays a safe descriptor without fabricating a snapshot`() {
        val createCampaign = createCampaignCommand("all-mutations")
        val draft = service.createCampaign(createCampaign).applied()
        service.createCampaign(createCampaign).shouldReplay(createCampaign.campaignId, draft.revision, "CAMPAIGN_CREATED")

        val update = UpdateCampaignPolicyCommand(
            TENANT,
            draft.campaignId,
            draft.revision,
            VoucherPoolPolicy.of(3, 1.minutes, 5.minutes, 1),
            key("policy-all-mutations"),
        )
        val updated = service.updatePolicy(update).applied()
        service.updatePolicy(update).shouldReplay(updated.campaignId, updated.revision, "CAMPAIGN_POLICY_UPDATED")

        val activate = CampaignRevisionCommand(TENANT, updated.campaignId, updated.revision, key("activate-all-mutations"))
        val campaign = service.activateCampaign(activate).applied()
        service.activateCampaign(activate).shouldReplay(campaign.campaignId, campaign.revision, "CAMPAIGN_ACTIVATED")

        val createBatch = createBatchCommand(campaign, "all-mutations", 2, listOf("ALL-0"))
        val batch = service.createImportBatch(createBatch).applied()
        service.createImportBatch(createBatch).shouldReplay(batch.batchId, batch.revision, "BATCH_CREATED")

        val chunk = ImportChunkCommand(
            TENANT,
            batch.batchId,
            campaign.campaignId,
            1,
            manifest("all-mutations"),
            listOf("ALL-1"),
            batch.revision,
            key("chunk-all-mutations"),
        )
        val completed = service.importChunk(chunk).applied()
        service.importChunk(chunk).shouldReplay(batch.batchId, completed.revision, "BATCH_CHECKPOINTED")

        val activateBatch = BatchRevisionCommand(
            TENANT,
            campaign.campaignId,
            batch.batchId,
            completed.revision,
            key("activate-batch-all-mutations"),
        )
        val active = service.activateBatch(activateBatch).applied()
        service.activateBatch(activateBatch).shouldReplay(batch.batchId, active.revision, "BATCH_ACTIVATED")

        val generatedBatch = createBatch(campaign, "all-mutations-generated", 1, emptyList(), BatchSourceKind.GENERATED)
        val generate = GenerateChunkCommand(
            TENANT,
            generatedBatch.batchId,
            campaign.campaignId,
            0,
            manifest("all-mutations-generated"),
            1,
            generatedBatch.revision,
            key("generate-all-mutations"),
        )
        val generated = service.generateChunk(generate).applied()
        service.generateChunk(generate).shouldReplay(generatedBatch.batchId, generated.revision, "BATCH_CHECKPOINTED")
    }

    @Test
    fun `import expected revision participates in the idempotency fingerprint`() {
        val fixture = campaignAndBatch("import-revision-fingerprint", expected = 2, initial = listOf("IMPORT-FP-0"))
        val command = ImportChunkCommand(
            TENANT,
            fixture.batch.batchId,
            fixture.campaign.campaignId,
            1,
            manifest("import-revision-fingerprint"),
            listOf("IMPORT-FP-1"),
            fixture.batch.revision,
            key("import-revision-fingerprint"),
        )
        val applied = service.importChunk(command).applied()

        assertFailsWith<BatchCommandException> {
            service.importChunk(
                ImportChunkCommand(
                    command.tenantId,
                    command.batchId,
                    command.campaignId,
                    command.firstOrdinal,
                    command.manifestDigest,
                    command.codes,
                    applied.revision,
                    command.idempotencyKey,
                ),
            )
        }.reason shouldBeEqualTo BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT
    }

    @Test
    fun `generation expected revision participates in the idempotency fingerprint`() {
        val campaign = createCampaign("generate-revision-fingerprint")
        val batch = createBatch(
            campaign,
            "generate-revision-fingerprint",
            expected = 1,
            initial = emptyList(),
            sourceKind = BatchSourceKind.GENERATED,
        )
        val command = GenerateChunkCommand(
            TENANT,
            batch.batchId,
            campaign.campaignId,
            0,
            manifest("generate-revision-fingerprint"),
            1,
            batch.revision,
            key("generate-revision-fingerprint"),
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
                TENANT,
                fixture.batch.batchId,
                fixture.campaign.campaignId,
                1,
                manifest("concurrent-import-revision"),
                listOf("IMPORT-WINNER-A"),
                fixture.batch.revision,
                key("concurrent-import-revision-a"),
            ),
            ImportChunkCommand(
                TENANT,
                fixture.batch.batchId,
                fixture.campaign.campaignId,
                1,
                manifest("concurrent-import-revision"),
                listOf("IMPORT-WINNER-B"),
                fixture.batch.revision,
                key("concurrent-import-revision-b"),
            ),
        )

        val outcomes = executeConcurrently(commands) { service.importChunk(it).applied() }

        outcomes.filterIsInstance<BatchSnapshot>() shouldHaveSize 1
        outcomes.count { it == BatchCommandFailure.STALE_REVISION } shouldBeEqualTo 1
        val stored = batchRow(fixture.batch.batchId)
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
                TENANT,
                batch.batchId,
                campaign.campaignId,
                0,
                manifest("concurrent-generate-revision"),
                1,
                batch.revision,
                key("concurrent-generate-revision-a"),
            ),
            GenerateChunkCommand(
                TENANT,
                batch.batchId,
                campaign.campaignId,
                0,
                manifest("concurrent-generate-revision"),
                2,
                batch.revision,
                key("concurrent-generate-revision-b"),
            ),
        )

        val outcomes = executeConcurrently(commands) { service.generateChunk(it).applied() }

        outcomes.filterIsInstance<BatchSnapshot>() shouldHaveSize 1
        outcomes.count { it == BatchCommandFailure.STALE_REVISION } shouldBeEqualTo 1
        val stored = batchRow(batch.batchId)
        stored.revision shouldBeEqualTo batch.revision + 1
        stored.nextSourceOrdinal shouldBeEqualTo stored.acceptedCount
        entryCount(batch.batchId) shouldBeEqualTo stored.acceptedCount
    }

    @Test
    fun `same key conflict and active owner prevent a second campaign effect`() {
        val command = createCampaignCommand("idempotency-conflict")
        service.createCampaign(command).applied()
        assertFailsWith<BatchCommandException> {
            service.createCampaign(command.copy(endsAt = command.endsAt.plusSeconds(1)))
        }.reason shouldBeEqualTo BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT
        campaignCount(command.campaignId) shouldBeEqualTo 1

        val inProgress = createCampaignCommand("idempotency-progress")
        val idempotency = JdbcVoucherPoolIdempotencyRepository(digests)
        val decision = transactionExecutor().operatorTransaction {
            idempotency.acquire(
                CommandScope(TENANT, "campaign-create"),
                inProgress.idempotencyKey,
                createCampaignFingerprint(inProgress),
            )
        }
        (decision is IdempotencyDecision.Execute).shouldBeTrue()
        assertFailsWith<BatchCommandException> { service.createCampaign(inProgress) }
            .reason shouldBeEqualTo BatchCommandFailure.COMMAND_IN_PROGRESS
        campaignCount(inProgress.campaignId) shouldBeEqualTo 0
    }

    @Test
    fun `business effect rolls back when idempotency finalize fails and same key can retry`() {
        val command = createCampaignCommand("finalize-rollback")
        installFinalizeFailureTrigger()
        assertFailsWith<RuntimeException> { service.createCampaign(command) }
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
                TENANT,
                draft.campaignId,
                draft.revision,
                VoucherPoolPolicy.of(3, 1.minutes, 5.minutes, 1),
                key("policy-draft"),
            ),
        ).applied()
        val active = service.activateCampaign(
            CampaignRevisionCommand(TENANT, updated.campaignId, updated.revision, key("policy-activate")),
        ).applied()
        assertFailsWith<BatchCommandException> {
            service.updatePolicy(
                UpdateCampaignPolicyCommand(
                    TENANT,
                    active.campaignId,
                    active.revision,
                    VoucherPoolPolicy.of(4, 1.minutes, 5.minutes, 1),
                    key("policy-active"),
                ),
            )
        }.reason shouldBeEqualTo BatchCommandFailure.INVALID_STATE

        execute("UPDATE voucher_pool_campaigns SET state='PAUSED',revision=revision+1 WHERE campaign_id='${active.campaignId}'")
        val pausedRevision = campaignRevision(active.campaignId)
        service.updatePolicy(
            UpdateCampaignPolicyCommand(
                TENANT,
                active.campaignId,
                pausedRevision,
                VoucherPoolPolicy.of(5, 1.minutes, 5.minutes, 1),
                key("policy-paused"),
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
                    TENANT,
                    fixture.campaign.campaignId,
                    fixture.batch.batchId,
                    fixture.batch.revision,
                    key("activate-tampered-checkpoint"),
                ),
            )
        }.reason shouldBeEqualTo BatchCommandFailure.ACTIVATION_INCOMPLETE
    }

    @Test
    fun `validation failure records only bounded evidence and blocks activation`() {
        val fixture = campaignAndBatch("invalid", expected = 3, initial = listOf("VALID-0"))
        val before = batchRow(fixture.batch.batchId)
        val command = import(fixture, 1, listOf("VALID-1", "bad\ncode"))
        val rejection = assertFailsWith<PreparedChunkRejectedException> { service.importChunk(command) }
        val failed = batchRow(fixture.batch.batchId)

        failed.state shouldBeEqualTo BatchState.FAILED_TERMINAL
        failed.nextSourceOrdinal shouldBeEqualTo 3
        failed.acceptedCount shouldBeEqualTo before.acceptedCount
        failed.rejectedCount shouldBeEqualTo 2
        failed.checkpointDigest shouldBeEqualTo before.checkpointDigest
        entryCount(fixture.batch.batchId) shouldBeEqualTo 1
        rejection.evidenceCode shouldBeEqualTo failed.lastFailureCode
        rejection.evidenceCode shouldNotContain "bad"
        val plainOracle = java.security.MessageDigest.getInstance("SHA-256")
            .digest("bad\ncode".toByteArray()).take(8).toByteArray().joinToString("") { "%02x".format(it) }
        failed.lastFailureCode.orEmpty() shouldNotContain plainOracle
        failed.lastFailureCode.orEmpty().length.let { it <= 64 }.shouldBeTrue()
        val replay = service.importChunk(command) as MutationResult.Replay
        replay.descriptor.terminalCode shouldBeEqualTo VoucherPoolErrorCode.BATCH_FAILED_TERMINAL
        assertFailsWith<BatchCommandException> {
            service.activateBatch(BatchRevisionCommand(TENANT, fixture.campaign.campaignId, fixture.batch.batchId, failed.revision, key("activate-invalid")))
        }.reason shouldBeEqualTo BatchCommandFailure.ACTIVATION_INCOMPLETE
    }

    @Test
    fun `initial mixed rejection creates only a terminal batch and terminal replay`() {
        val campaign = createCampaign("initial-invalid")
        val command = createBatchCommand(campaign, "initial-invalid", 2, listOf("INITIAL-VALID", "bad\ninitial"))
        val rejection = assertFailsWith<PreparedChunkRejectedException> { service.createImportBatch(command) }
        val failed = batchRow(command.batchId)

        failed.state shouldBeEqualTo BatchState.FAILED_TERMINAL
        failed.nextSourceOrdinal shouldBeEqualTo 2
        failed.acceptedCount shouldBeEqualTo 0
        failed.rejectedCount shouldBeEqualTo 2
        failed.checkpointDigest.shouldBeNull()
        failed.lastFailureCode shouldBeEqualTo rejection.evidenceCode
        entryCount(command.batchId) shouldBeEqualTo 0
        val replay = service.createImportBatch(command) as MutationResult.Replay
        replay.descriptor.terminalCode shouldBeEqualTo VoucherPoolErrorCode.BATCH_FAILED_TERMINAL
        execute(
            "UPDATE voucher_pool_http_idempotency SET expires_at=statement_timestamp()-interval '1 second' " +
                "WHERE tenant_id='$TENANT' AND operation='batch-create'",
        )
        transactionExecutor().operatorTransaction { JdbcVoucherPoolIdempotencyRepository(digests).purgeDescriptors(10) }
        val expired = service.createImportBatch(command) as MutationResult.Expired
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
            TENANT,
            fixture.batch.batchId,
            fixture.campaign.campaignId,
            1,
            manifest("crypto-rejection"),
            listOf(rawSecret),
            before.revision,
            key("crypto-rejection-chunk"),
        )

        val rejection = assertFailsWith<PreparedChunkRejectedException> { service.importChunk(command) }
        val failed = batchRow(fixture.batch.batchId)
        failed.state shouldBeEqualTo BatchState.FAILED_TERMINAL
        failed.nextSourceOrdinal shouldBeEqualTo 2
        failed.acceptedCount shouldBeEqualTo before.acceptedCount
        failed.rejectedCount shouldBeEqualTo 1
        failed.checkpointDigest shouldBeEqualTo before.checkpointDigest
        entryCount(fixture.batch.batchId) shouldBeEqualTo 1
        dedupCount(fixture.batch.batchId) shouldBeEqualTo 1
        failed.lastFailureCode shouldBeEqualTo rejection.evidenceCode
        failed.lastFailureCode.orEmpty() shouldNotContain rawSecret
        val plainOracle = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rawSecret.toByteArray()).take(8).toByteArray().joinToString("") { "%02x".format(it) }
        failed.lastFailureCode.orEmpty() shouldNotContain plainOracle
        (failed.lastFailureCode.orEmpty().length <= 64).shouldBeTrue()

        val replay = service.importChunk(command) as MutationResult.Replay
        replay.descriptor.terminalCode shouldBeEqualTo VoucherPoolErrorCode.BATCH_FAILED_TERMINAL
        execute(
            "UPDATE voucher_pool_http_idempotency SET expires_at=statement_timestamp()-interval '1 second' " +
                "WHERE tenant_id='$TENANT' AND operation='batch-import-chunk'",
        )
        transactionExecutor().operatorTransaction { JdbcVoucherPoolIdempotencyRepository(digests).purgeDescriptors(10) }
        val expired = service.importChunk(command) as MutationResult.Expired
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
        service.importChunk(import(fixture, 2, codes(2, 2))).applied().nextSourceOrdinal shouldBeEqualTo 4
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
                    TENANT,
                    batch.batchId,
                    campaign.campaignId,
                    1,
                    manifest("duplicate-b"),
                    listOf("GLOBAL-CODE"),
                    batch.revision,
                    key("duplicate-code"),
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
        val original = createBatchCommand(campaign, "create-replay-authority", 2, listOf("CREATE-0"))
            .withActivatesAt(POSTGRES_ROUNDING_INSTANT)
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

        assertFailsWith<BatchCommandException> { service.createImportBatch(conflicting) }
            .reason shouldBeEqualTo BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT
        batchRow(created.batchId) shouldBeEqualTo created
        entryCount(created.batchId) shouldBeEqualTo 1
    }

    @Test
    fun `concurrent exact campaign and batch creates with different keys converge on one effect`() {
        service = newService(repository = BarrierCreateRepository(JdbcVoucherPoolRepository()))
        val campaignCommand = createCampaignCommand("concurrent-create").copy(
            startsAt = POSTGRES_ROUNDING_INSTANT,
            endsAt = POSTGRES_ROUNDING_INSTANT.plusSeconds(3_600),
        )
        val secondCampaignCommand = campaignCommand.copy(idempotencyKey = key("concurrent-create-campaign-second"))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val campaigns = listOf(campaignCommand, secondCampaignCommand).map { command ->
                pool.submit<CampaignSnapshot> { service.createCampaign(command).applied() }
            }.map { it.get(15, TimeUnit.SECONDS) }
            campaigns.distinct() shouldHaveSize 1
            campaignCount(campaignCommand.campaignId) shouldBeEqualTo 1

            val draft = campaigns.first()
            val campaign = service.activateCampaign(
                CampaignRevisionCommand(TENANT, draft.campaignId, draft.revision, key("concurrent-create-activate")),
            ).applied()
            val firstBatch = createBatchCommand(campaign, "concurrent-create", 1, listOf("CONCURRENT-0"))
                .withActivatesAt(POSTGRES_ROUNDING_INSTANT.plusSeconds(60))
            val secondBatch = CreateImportBatchCommand(
                firstBatch.tenantId,
                firstBatch.batchId,
                firstBatch.campaignId,
                firstBatch.sourceKind,
                firstBatch.manifestDigest,
                firstBatch.requestFingerprint,
                firstBatch.expectedCount,
                firstBatch.activatesAt,
                firstBatch.expiresAt,
                firstBatch.initialCodes,
                key("concurrent-create-batch-second"),
            )
            val batches = listOf(firstBatch, secondBatch).map { command ->
                pool.submit<BatchSnapshot> { service.createImportBatch(command).applied() }
            }.map { it.get(15, TimeUnit.SECONDS) }
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
            campaign,
            "terminal-authority-race",
            1,
            listOf("bad\ncreate"),
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
                activatesAt = failingCommand.activatesAt.plusSeconds(1),
                expiresAt = failingCommand.activatesAt.plusSeconds(600),
                initialCodes = listOf("HEALTHY-WINNER"),
                idempotencyKey = key("terminal-authority-race-winner"),
            )
            val healthy = service.createImportBatch(healthyCommand).applied()
            raceRepository.releaseTerminalRecovery()

            val wrapped = assertFailsWith<ExecutionException> { failingFuture.get(15, TimeUnit.SECONDS) }
            (wrapped.cause is PreparedChunkRejectedException).shouldBeTrue()
            batchRow(healthy.batchId) shouldBeEqualTo healthy
            healthy.state shouldBeEqualTo BatchState.STAGING
            healthy.nextSourceOrdinal shouldBeEqualTo 1
            healthy.acceptedCount shouldBeEqualTo 1
            healthy.rejectedCount shouldBeEqualTo 0
            entryCount(healthy.batchId) shouldBeEqualTo 1
            val replay = failingService.createImportBatch(failingCommand) as MutationResult.Replay
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
                    TENANT,
                    fixture.batch.batchId,
                    fixture.campaign.campaignId,
                    1,
                    manifest("nonce-collision"),
                    listOf("NONCE-1"),
                    fixture.batch.revision,
                    key("nonce-collision-chunk"),
                ),
            )
        }.reason shouldBeEqualTo BatchCommandFailure.RETRYABLE_INTEGRITY_COLLISION
        batchRow(fixture.batch.batchId) shouldBeEqualTo fixture.batch
        entryCount(fixture.batch.batchId) shouldBeEqualTo 1

        service = newService()
        val unknown = campaignAndBatch("unknown-integrity", expected = 2, initial = listOf("UNKNOWN-0"))
        installUnknownIntegrityTrigger(1)
        val unknownCommand = ImportChunkCommand(
            TENANT,
            unknown.batch.batchId,
            unknown.campaign.campaignId,
            1,
            manifest("unknown-integrity"),
            listOf("UNKNOWN-1"),
            unknown.batch.revision,
            key("unknown-integrity-chunk"),
        )
        val failure = assertFailsWith<RuntimeException> { service.importChunk(unknownCommand) }
        dropUnknownIntegrityTrigger()
        (failure is BatchCommandException).shouldBeFalse()
        batchRow(unknown.batch.batchId) shouldBeEqualTo unknown.batch
        entryCount(unknown.batch.batchId) shouldBeEqualTo 1
        assertFailsWith<BatchCommandException> { service.importChunk(unknownCommand) }
            .reason shouldBeEqualTo BatchCommandFailure.COMMAND_IN_PROGRESS
    }

    @Test
    fun `generation rollback regenerates codes without persisting a seed`() {
        val generated = ArrayDeque(listOf("GEN-A", "GEN-B", "GEN-C", "GEN-D"))
        val source = object : GeneratedVoucherCodeSource {
            override val deterministic: Boolean = true
            override fun nextCode(): String = generated.removeFirst()
        }
        service = newService(source, VoucherPoolRuntimeProfile.LOOPBACK_TEST)
        val campaign = createCampaign("generated")
        val batch = createBatch(
            campaign,
            "generated",
            expected = 2,
            initial = emptyList(),
            sourceKind = BatchSourceKind.GENERATED,
        )
        installOrdinalFailureTrigger(1)
        assertFailsWith<RuntimeException> {
            service.generateChunk(
                GenerateChunkCommand(
                    TENANT,
                    batch.batchId,
                    campaign.campaignId,
                    0,
                    manifest("generated"),
                    2,
                    batch.revision,
                    key("generate-0"),
                ),
            )
        }
        dropOrdinalFailureTrigger()

        service.generateChunk(
            GenerateChunkCommand(
                TENANT,
                batch.batchId,
                campaign.campaignId,
                0,
                manifest("generated"),
                2,
                batch.revision,
                key("generate-0"),
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
            service.activateBatch(BatchRevisionCommand(TENANT, fixture.campaign.campaignId, fixture.batch.batchId, fixture.batch.revision, key("activate-incomplete")))
        }.reason shouldBeEqualTo BatchCommandFailure.ACTIVATION_INCOMPLETE

        val completed = service.importChunk(import(fixture, 2, codes(2, 1))).applied()
        val active = service.activateBatch(
            BatchRevisionCommand(TENANT, fixture.campaign.campaignId, fixture.batch.batchId, completed.revision, key("activate-complete")),
        ).applied()
        active.state shouldBeEqualTo BatchState.ACTIVE

        val gap = campaignAndBatch("gap", expected = 3, initial = codes(10, 3))
        execute("DELETE FROM voucher_pool_entries WHERE tenant_id='$TENANT' AND batch_id='${gap.batch.batchId}' AND source_ordinal=1")
        assertFailsWith<BatchCommandException> {
            service.activateBatch(BatchRevisionCommand(TENANT, gap.campaign.campaignId, gap.batch.batchId, gap.batch.revision, key("activate-gap")))
        }.reason shouldBeEqualTo BatchCommandFailure.ACTIVATION_INCOMPLETE
    }

    @Test
    fun `ten thousand entry batch finalizes through bounded chunks`() {
        val tracking = TrackingVoucherPoolRepository(JdbcVoucherPoolRepository())
        service = newService(repository = tracking)
        val fixture = campaignAndBatch("ten-thousand", expected = 10_000, initial = codes(0, 500))
        var snapshot = fixture.batch
        for (start in 500 until 10_000 step 500) {
            snapshot = service.importChunk(import(fixture, start.toLong(), codes(start, 500), snapshot.revision)).applied()
        }

        snapshot.nextSourceOrdinal shouldBeEqualTo 10_000
        snapshot.acceptedCount shouldBeEqualTo 10_000
        entryCount(snapshot.batchId) shouldBeEqualTo 10_000
        service.activateBatch(
            BatchRevisionCommand(TENANT, fixture.campaign.campaignId, snapshot.batchId, snapshot.revision, key("activate-ten-thousand")),
        ).applied().state shouldBeEqualTo BatchState.ACTIVE
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
            CampaignRevisionCommand(TENANT, campaign.campaignId, campaign.revision, key("activate-campaign-$suffix")),
        ).applied()
    }

    private fun createCampaignCommand(suffix: String) = CreateCampaignCommand(
        tenantId = TENANT,
        campaignId = UUID.nameUUIDFromBytes("campaign-$suffix".toByteArray()),
        startsAt = Instant.now().minusSeconds(60),
        endsAt = Instant.now().plusSeconds(3_600),
        policy = VoucherPoolPolicy.of(2, 1.minutes, 5.minutes, 1),
        idempotencyKey = key("create-campaign-$suffix"),
    )

    private fun createBatch(
        campaign: CampaignSnapshot,
        suffix: String,
        expected: Long,
        initial: List<String>,
        sourceKind: BatchSourceKind = BatchSourceKind.IMPORTED,
    ): BatchSnapshot = service.createImportBatch(createBatchCommand(campaign, suffix, expected, initial, sourceKind)).applied()

    private fun createBatchCommand(
        campaign: CampaignSnapshot,
        suffix: String,
        expected: Long,
        initial: List<String>,
        sourceKind: BatchSourceKind = BatchSourceKind.IMPORTED,
    ) = CreateImportBatchCommand(
            tenantId = TENANT,
            batchId = UUID.nameUUIDFromBytes("batch-$suffix".toByteArray()),
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
        UUID.nameUUIDFromBytes("batch-resume".toByteArray()) -> "resume"
        UUID.nameUUIDFromBytes("batch-invalid".toByteArray()) -> "invalid"
        UUID.nameUUIDFromBytes("batch-crash".toByteArray()) -> "crash"
        UUID.nameUUIDFromBytes("batch-activation".toByteArray()) -> "activation"
        UUID.nameUUIDFromBytes("batch-ten-thousand".toByteArray()) -> "ten-thousand"
        UUID.nameUUIDFromBytes("batch-tampered-checkpoint".toByteArray()) -> "tampered-checkpoint"
        else -> error("unknown fixture")
    }

    private fun newService(
        source: GeneratedVoucherCodeSource = SecureRandomVoucherCodeSource(),
        runtimeProfile: VoucherPoolRuntimeProfile = VoucherPoolRuntimeProfile.PRODUCTION,
        envelopeCrypto: VoucherEnvelopeCrypto = crypto,
        repository: VoucherPoolRepository = JdbcVoucherPoolRepository(),
    ): JdbcCampaignBatchCommandService {
        val manager = SpringTransactionManager(dataSource, DatabaseConfig {}, false)
        return JdbcCampaignBatchCommandService(
            VoucherPoolJdbcExecutor(DatabasePermitGate.default(32), manager),
            repository,
            JdbcVoucherPoolIdempotencyRepository(digests),
            digests,
            envelopeCrypto,
            source,
            runtimeProfile,
        )
    }

    private fun transactionExecutor(): VoucherPoolJdbcExecutor {
        val manager = SpringTransactionManager(dataSource, DatabaseConfig {}, false)
        return VoucherPoolJdbcExecutor(DatabasePermitGate.default(32), manager)
    }

    private fun digestService(): VoucherDigestService = VoucherDigestService(
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
        java.security.MessageDigest.getInstance("SHA-256").digest("manifest-$suffix".toByteArray()),
    )

    private fun codes(offset: Int, count: Int): List<String> = List(count) { "CODE-${offset + it}" }

    private fun batchRow(batchId: UUID): BatchSnapshot = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT state,next_source_ordinal,expected_count,accepted_count,rejected_count,checkpoint_digest,last_failure_code,revision,campaign_id FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=?",
        ).use { statement ->
            statement.setString(1, TENANT)
            statement.setObject(2, batchId)
            statement.executeQuery().use { result ->
                result.next()
                BatchSnapshot(
                    TENANT,
                    batchId,
                    result.getObject("campaign_id", UUID::class.java),
                    BatchState.valueOf(result.getString("state")),
                    result.getLong("next_source_ordinal"),
                    result.getLong("expected_count"),
                    result.getLong("accepted_count"),
                    result.getLong("rejected_count"),
                    result.getBytes("checkpoint_digest")?.let(DigestValue::of),
                    result.getString("last_failure_code"),
                    result.getLong("revision"),
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
        connection.createStatement().executeQuery(sql).use { it.next(); it.getLong(1) }
    }

    private fun execute(sql: String) {
        dataSource.connection.use { it.createStatement().execute(sql) }
    }

    private fun installOrdinalFailureTrigger(ordinal: Long) {
        execute(
            """CREATE FUNCTION fail_ingest_ordinal() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.source_ordinal=$ordinal THEN RAISE EXCEPTION 'simulated crash' USING ERRCODE='40001'; END IF; RETURN NEW; END $$;
                CREATE TRIGGER fail_ingest_ordinal BEFORE INSERT ON voucher_pool_entries FOR EACH ROW EXECUTE FUNCTION fail_ingest_ordinal()""",
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
                FOR EACH ROW EXECUTE FUNCTION fail_idempotency_finalize()""",
        )
    }

    private fun dropFinalizeFailureTrigger() {
        execute(
            "DROP TRIGGER IF EXISTS fail_idempotency_finalize ON voucher_pool_http_idempotency; " +
                "DROP FUNCTION IF EXISTS fail_idempotency_finalize()",
        )
    }

    private fun installUnknownIntegrityTrigger(ordinal: Long) {
        execute(
            """CREATE FUNCTION fail_unknown_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.source_ordinal=$ordinal THEN
                  RAISE EXCEPTION 'unknown integrity authority' USING ERRCODE='23505',CONSTRAINT='unknown_integrity_authority';
                END IF; RETURN NEW; END $$;
                CREATE TRIGGER fail_unknown_integrity BEFORE INSERT ON voucher_pool_entries
                FOR EACH ROW EXECUTE FUNCTION fail_unknown_integrity()""",
        )
    }

    private fun dropUnknownIntegrityTrigger() {
        execute(
            "DROP TRIGGER IF EXISTS fail_unknown_integrity ON voucher_pool_entries; " +
                "DROP FUNCTION IF EXISTS fail_unknown_integrity()",
        )
    }

    private fun createCampaignFingerprint(command: CreateCampaignCommand) = VoucherPoolFingerprint.command(
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
        replay.descriptor.effectId shouldBeEqualTo effectId
        replay.descriptor.revision shouldBeEqualTo revision
        replay.descriptor.outcome shouldBeEqualTo outcome
    }

    private fun <T> executeConcurrently(commands: List<T>, action: (T) -> BatchSnapshot): List<Any> {
        val pool = Executors.newFixedThreadPool(commands.size)
        val start = CyclicBarrier(commands.size)
        return try {
            commands.map { command ->
                pool.submit<Any> {
                    start.await(10, TimeUnit.SECONDS)
                    try {
                        action(command)
                    } catch (failure: BatchCommandException) {
                        failure.reason
                    }
                }
            }.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun adminConnection(): Connection = DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username ?: PostgreSQLServer.USERNAME,
        postgres.password ?: PostgreSQLServer.PASSWORD,
    )

    private data class Fixture(val campaign: CampaignSnapshot, val batch: BatchSnapshot)

    private class BoundaryCheckingCrypto(private val delegate: VoucherEnvelopeCrypto) : VoucherEnvelopeCrypto {
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

    private class FixedNonceCrypto(private val delegate: VoucherEnvelopeCrypto) : VoucherEnvelopeCrypto {
        override fun encrypt(entryIdentity: EntryIdentity, code: CanonicalVoucherCode): EncryptedVoucherCode =
            delegate.encrypt(entryIdentity, code).copy(
                codeNonce = ByteArray(12) { 11 },
                wrapNonce = ByteArray(12) { 12 },
            )

        override fun decryptAndVerify(
            entryIdentity: EntryIdentity,
            encrypted: EncryptedVoucherCode,
            expectedStableDedup: VoucherDigest,
        ): CanonicalVoucherCode = delegate.decryptAndVerify(entryIdentity, encrypted, expectedStableDedup)
    }

    private class RejectingCrypto(private val delegate: VoucherEnvelopeCrypto) : VoucherEnvelopeCrypto {
        override fun encrypt(entryIdentity: EntryIdentity, code: CanonicalVoucherCode): EncryptedVoucherCode =
            throw VoucherCryptoException(VoucherCryptoFailureReason.INVALID_CIPHERTEXT)

        override fun decryptAndVerify(
            entryIdentity: EntryIdentity,
            encrypted: EncryptedVoucherCode,
            expectedStableDedup: VoucherDigest,
        ): CanonicalVoucherCode = delegate.decryptAndVerify(entryIdentity, encrypted, expectedStableDedup)
    }

    private class TrackingVoucherPoolRepository(
        private val delegate: VoucherPoolRepository,
    ) : VoucherPoolRepository by delegate {
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
    ) : VoucherPoolRepository by delegate {
        private val campaignBarrier = CyclicBarrier(2)
        private val batchBarrier = CyclicBarrier(2)

        override fun createCampaign(campaign: CampaignRecord): CampaignRecord {
            campaignBarrier.await(10, TimeUnit.SECONDS)
            return delegate.createCampaign(campaign)
        }

        override fun createBatch(batch: BatchRecord): BatchRecord {
            batchBarrier.await(10, TimeUnit.SECONDS)
            return delegate.createBatch(batch)
        }
    }

    private class TerminalRecoveryRaceRepository(
        private val delegate: VoucherPoolRepository,
    ) : VoucherPoolRepository by delegate {
        private val terminalRecoveryStarted = CountDownLatch(1)
        private val winnerCommitted = CountDownLatch(1)
        private val campaignLockCalls = ThreadLocal.withInitial { 0 }

        override fun lockCampaignForShare(tenantId: String, campaignId: UUID): CampaignRecord {
            if (Thread.currentThread().name == TERMINAL_RACE_THREAD) {
                val call = campaignLockCalls.get() + 1
                campaignLockCalls.set(call)
                if (call == 2) {
                    terminalRecoveryStarted.countDown()
                    check(winnerCommitted.await(10, TimeUnit.SECONDS)) { "different-authority winner did not commit" }
                }
            }
            return delegate.lockCampaignForShare(tenantId, campaignId)
        }

        fun awaitTerminalRecovery(): Boolean = terminalRecoveryStarted.await(10, TimeUnit.SECONDS)

        fun releaseTerminalRecovery() = winnerCommitted.countDown()
    }

    companion object {
        private val postgres = PostgreSQLServer.Launcher.postgres
        private val POSTGRES_ROUNDING_INSTANT = Instant.parse("2026-07-21T13:47:39.999999789Z")
        private const val TENANT = "tenant-ingest"
        private const val TERMINAL_RACE_THREAD = "failing-create-terminal-race"
    }
}
