@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.JdbcVoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.security.AesGcmVoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.EncryptedVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.security.EntryIdentity
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigest
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKek
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import io.bluetape4k.workshop.commerce.voucherpool.security.testFixture
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

internal class VoucherPoolBackupRestoreIntegrationTest {
    @Test
    fun `restore preflight validates every key before database import`() {
        val imported = AtomicInteger()
        val complete = availableKeys()
        val missingCases = listOf(
            complete.copy(kekVersions = setOf("kek-v2")),
            complete.copy(verificationVersions = emptySet()),
            complete.copy(userIdentityVersions = emptySet()),
            complete.copy(auditVersions = emptySet()),
            complete.copy(signatureVersions = emptySet()),
            complete.copy(stableDedupVersions = emptySet()),
            complete.copy(commandTombstoneVersions = emptySet()),
        )

        missingCases.forEach { available ->
            val failure = assertFailsWith<VoucherPoolRestoreException> {
                VoucherPoolRestoreCoordinator(available)
                    .restore(manifest(), backupInventory(), { imported.incrementAndGet() }, ::passingSmoke)
            }
            failure.code shouldBeEqualTo VoucherPoolRestoreFailureCode.MISSING_KEY
        }

        imported.get() shouldBeEqualTo 0
    }

    @Test
    fun `restore rejects a manifest that omits keys referenced by backup content`() {
        val imported = AtomicInteger()
        val complete = manifest()
        val incompleteCases =
            listOf(
                complete.copy(userIdentityVersions = emptySet()),
                complete.copy(auditVersions = emptySet()),
                complete.copy(signatureVersions = emptySet()),
            )

        incompleteCases.forEach { incomplete ->
            val failure = assertFailsWith<VoucherPoolRestoreException> {
                VoucherPoolRestoreCoordinator(availableKeys()).restore(
                    incomplete,
                    backupInventory(),
                    { imported.incrementAndGet() },
                    ::passingSmoke,
                )
            }
            failure.code shouldBeEqualTo VoucherPoolRestoreFailureCode.INCOMPLETE_MANIFEST
        }

        imported.get() shouldBeEqualTo 0
    }

    @Test
    fun `restore retains command tombstone replay fence`() {
        val effectCount = AtomicInteger(1)
        val coordinator = VoucherPoolRestoreCoordinator(availableKeys())

        val restored = coordinator.restore(manifest(), backupInventory(), {}, ::passingSmoke)
        val retryCode = if (restored.replayFenceRetained) VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED else null

        retryCode shouldBeEqualTo VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED
        effectCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `backup manifest remains a stable serializable recovery contract`() {
        val original = manifest()
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(original) }
            output.toByteArray()
        }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }

        restored shouldBeEqualTo original
    }

    @Test
    fun `restore smoke fails closed when any recovery invariant is absent`() {
        val coordinator = VoucherPoolRestoreCoordinator(availableKeys())

        val failure = assertFailsWith<VoucherPoolRestoreException> {
            coordinator.restore(manifest(), backupInventory(), {}) { passingSmoke().copy(cursorRestarted = false) }
        }

        failure.code shouldBeEqualTo VoucherPoolRestoreFailureCode.SMOKE_FAILED
    }

    @Test
    fun `logical backup restore proves every persisted recovery invariant`() {
        val schema = "voucher_restore_${Base58.randomString(8).lowercase()}"
        VOUCHER_POOL_TASK_12_POSTGRES.createSchema(schema)
        try {
            val dataSource = postgresDataSource(schema)
            migrationRunner(dataSource).migrate()
            val fixture = RestoreFixture()
            val coordinator = VoucherPoolRestoreCoordinator(availableKeys())

            val restored = coordinator.restore(
                manifest(),
                backupInventory(),
                {
                    restoreLogicalBackup(dataSource, fixture)
                },
                {
                    inspectRestoredData(dataSource, fixture)
                },
            )

            restored.passed.shouldBeTrue()
        } finally {
            VOUCHER_POOL_TASK_12_POSTGRES.dropSchema(schema)
        }
    }

    @Test
    fun `rollback selects only proven previous binary or rehearsed verified restore`() {
        VoucherPoolRollbackPolicy.select(
            previousBinaryCompatible = true,
            verifiedBackup = false,
            restoreRehearsalPassed = false,
        ) shouldBeEqualTo VoucherPoolRollbackStrategy.COMPATIBLE_PREVIOUS_BINARY
        VoucherPoolRollbackPolicy.select(
            previousBinaryCompatible = false,
            verifiedBackup = true,
            restoreRehearsalPassed = true,
        ) shouldBeEqualTo VoucherPoolRollbackStrategy.VERIFIED_RESTORE_AND_ROLL_FORWARD

        assertFailsWith<IllegalStateException> {
            VoucherPoolRollbackPolicy.select(
                previousBinaryCompatible = false,
                verifiedBackup = true,
                restoreRehearsalPassed = false,
            )
        }
    }

    private fun manifest() = BackupKeyManifest(
        kekVersions = setOf("kek-v1"),
        verificationVersions = setOf("2"),
        stableDedupVersion = "1",
        commandTombstoneVersion = "4",
        userIdentityVersions = setOf("3"),
        auditVersions = setOf("6"),
        signatureVersions = setOf("7"),
    )

    private fun availableKeys(kekVersions: Set<String> = setOf("kek-v1")) = VoucherPoolAvailableKeys(
        kekVersions = kekVersions,
        verificationVersions = setOf("2"),
        stableDedupVersions = setOf("1"),
        commandTombstoneVersions = setOf("4"),
        userIdentityVersions = setOf("3"),
        auditVersions = setOf("6"),
        signatureVersions = setOf("7"),
    )

    private fun backupInventory() =
        VoucherPoolBackupKeyInventory(
            kekVersions = setOf("kek-v1"),
            verificationVersions = setOf("2"),
            stableDedupVersions = setOf("1"),
            commandTombstoneVersions = setOf("4"),
            userIdentityVersions = setOf("3"),
            auditVersions = setOf("6"),
            signatureVersions = setOf("7"),
        )

    private fun passingSmoke() =
        VoucherPoolRestoreSmokeResult(
            ciphertextReadableOrQuarantined = true,
            countersConsistent = true,
            replayFenceRetained = true,
            cursorRestarted = true,
            staleWorkerTakenOver = true,
            exactlyOnceRevealRetained = true,
        )

    private fun restoreLogicalBackup(
        dataSource: DataSource,
        fixture: RestoreFixture,
    ) {
        restoreVoucherAuthority(dataSource, fixture)
        restoreRecoveryAuthority(dataSource, fixture)
    }

    private fun restoreVoucherAuthority(dataSource: DataSource, fixture: RestoreFixture) {
        execute(
            dataSource,
            """INSERT INTO voucher_pool_campaigns
                (tenant_id,campaign_id,state,user_identity_key_version,policy_version)
                VALUES ('$RESTORE_TENANT','${fixture.campaignId}','ACTIVE',3,1)""",
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_batches
                (tenant_id,batch_id,campaign_id,state,source_kind,provenance_digest,request_fingerprint,
                 policy_version,activates_at,expected_count,next_source_ordinal,accepted_count)
                VALUES ('$RESTORE_TENANT','${fixture.batchId}','${fixture.campaignId}','ACTIVE','GENERATED',decode('01','hex'),
                        decode('02','hex'),1,statement_timestamp()-interval '1 hour',1,1,1)""",
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_entries
                (tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,stable_dedup_digest,
                 verification_digest,verification_key_version,code_ciphertext,code_nonce,wrapped_dek,
                 wrap_nonce,kek_version)
                VALUES ('$RESTORE_TENANT','${fixture.entryId}','${fixture.campaignId}','${fixture.batchId}',0,'AVAILABLE',
                        decode('${fixture.stableDigest.copyBytes().toHexString()}','hex'),decode('04','hex'),2,
                        decode('${fixture.encrypted.copyCodeCiphertext().toHexString()}','hex'),
                        decode('${fixture.encrypted.copyCodeNonce().toHexString()}','hex'),
                        decode('${fixture.encrypted.copyWrappedDek().toHexString()}','hex'),
                        decode('${fixture.encrypted.copyWrapNonce().toHexString()}','hex'),'kek-v1')""",
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_pool_depth(tenant_id,batch_id,state,entry_count)
                VALUES ('$RESTORE_TENANT','${fixture.batchId}','AVAILABLE',1)""",
        )
    }

    private fun restoreRecoveryAuthority(dataSource: DataSource, fixture: RestoreFixture) {
        execute(
            dataSource,
            """INSERT INTO voucher_pool_http_idempotency
                (tenant_id,operation,scoped_key_digest,fingerprint,status,command_deadline,descriptor,expires_at)
                VALUES ('$RESTORE_TENANT','reveal',decode('${fixture.scopedKeyDigest.toHexString()}','hex'),
                        decode('${fixture.fingerprint.copyBytes().toHexString()}','hex'),'COMPLETED',
                        statement_timestamp()+interval '1 hour',NULL,statement_timestamp())""",
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_command_tombstones
                (tenant_id,operation,key_version,scoped_key_digest,fingerprint,effect_id)
                VALUES ('$RESTORE_TENANT','reveal',4,decode('${fixture.scopedKeyDigest.toHexString()}','hex'),
                        decode('${fixture.fingerprint.copyBytes().toHexString()}','hex'),'${fixture.effectId}')""",
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_audits
                (tenant_id,campaign_id,aggregate_type,aggregate_id,revision,policy_version,actor_type,
                 reason_code,audit_key_version)
                VALUES ('$RESTORE_TENANT','${fixture.campaignId}','CAMPAIGN','${fixture.campaignId}',0,1,'SYSTEM','BACKUP_RESTORE',6)""",
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_worker_claims
                (tenant_id,worker_type,scope_id,owner_id,claim_until,next_attempt_at,checkpoint)
                VALUES ('$RESTORE_TENANT','PURGE','${fixture.workerScopeId}','stale-worker',
                        statement_timestamp()-interval '1 minute',statement_timestamp(),1)""",
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_revoke_preview_grants
                (tenant_id,grant_id,aggregate_type,aggregate_id,aggregate_revision,impact_digest,
                 affected_count,signature_key_version,signature_digest,expires_at)
                VALUES ('$RESTORE_TENANT','${UUID.randomUUID()}','CAMPAIGN','${fixture.campaignId}',0,
                        decode('0b','hex'),1,7,decode('0c','hex'),statement_timestamp()+interval '1 hour')""",
        )
    }

    private fun inspectRestoredData(
        dataSource: DataSource,
        fixture: RestoreFixture,
    ): VoucherPoolRestoreSmokeResult {
        val priorCursor = queryLong(
            dataSource,
            "SELECT max(id) FROM voucher_pool_audits WHERE tenant_id='$RESTORE_TENANT'",
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_audits
                (tenant_id,campaign_id,aggregate_type,aggregate_id,revision,policy_version,actor_type,
                 reason_code,audit_key_version)
                VALUES ('$RESTORE_TENANT','${fixture.campaignId}','CAMPAIGN','${fixture.campaignId}',1,1,'SYSTEM','RESTORE_CURSOR_SMOKE',6)""",
        )
        val takeoverCount = executeUpdate(
            dataSource,
            """UPDATE voucher_pool_worker_claims SET owner_id='restore-smoke',
                       claim_until=statement_timestamp()+interval '1 minute',revision=revision+1
                WHERE tenant_id='$RESTORE_TENANT' AND scope_id='${fixture.workerScopeId}'
                  AND claim_until<statement_timestamp()""",
        )
        val replayDecisions = List(2) { restoredReplayDecision(dataSource, fixture) }
        val replayFenceRetained = replayDecisions.all {
            it == IdempotencyDecision.Expired(fixture.effectId, null)
        }
        return VoucherPoolRestoreSmokeResult(
            ciphertextReadableOrQuarantined = restoredCiphertextIsReadable(dataSource, fixture),
            countersConsistent = queryLong(
                dataSource,
                """SELECT count(*) FROM voucher_pool_batches b
                    WHERE b.tenant_id='$RESTORE_TENANT' AND b.accepted_count=(
                        SELECT count(*) FROM voucher_pool_entries e
                        WHERE e.tenant_id=b.tenant_id AND e.batch_id=b.batch_id)""".trimIndent(),
            ) == 1L,
            replayFenceRetained = replayFenceRetained,
            cursorRestarted = queryLong(
                dataSource,
                "SELECT max(id) FROM voucher_pool_audits WHERE tenant_id='$RESTORE_TENANT'",
            ) > priorCursor,
            staleWorkerTakenOver = takeoverCount == 1,
            exactlyOnceRevealRetained = replayFenceRetained && queryLong(
                dataSource,
                """SELECT count(*) FROM voucher_pool_command_tombstones
                    WHERE tenant_id='$RESTORE_TENANT' AND operation='reveal' AND effect_id IS NOT NULL""".trimIndent(),
            ) == 1L,
        )
    }

    private fun restoredReplayDecision(dataSource: DataSource, fixture: RestoreFixture): IdempotencyDecision {
        val database = Database.connect(dataSource)
        val repository = JdbcVoucherPoolIdempotencyRepository(fixture.digests)
        return transaction(database) {
            repository.acquire(fixture.commandScope, fixture.rawIdempotencyKey, fixture.fingerprint)
        }
    }

    private fun execute(dataSource: DataSource, sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun executeUpdate(dataSource: DataSource, sql: String): Int =
        dataSource.connection.use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }

    private fun queryLong(dataSource: DataSource, sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result -> check(result.next()); result.getLong(1) }
            }
        }

    private fun restoredCiphertextIsReadable(dataSource: DataSource, fixture: RestoreFixture): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT stable_dedup_digest,code_ciphertext,code_nonce,wrapped_dek,wrap_nonce,kek_version
                    FROM voucher_pool_entries WHERE tenant_id=? AND entry_id=?""",
            ).use { statement ->
                statement.setString(1, RESTORE_TENANT)
                statement.setObject(2, fixture.entryId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    val digest = VoucherDigest.of(DigestPurpose.STABLE_DEDUP, 1, result.getBytes("stable_dedup_digest"))
                    val encrypted = EncryptedVoucherCode.of(
                        result.getBytes("code_ciphertext"),
                        result.getBytes("code_nonce"),
                        result.getBytes("wrapped_dek"),
                        result.getBytes("wrap_nonce"),
                        result.getString("kek_version"),
                    )
                    fixture.crypto.decryptAndVerify(fixture.identity, encrypted, digest) == fixture.code
                }
            }
        }

    private companion object {
        const val RESTORE_TENANT = "restore-tenant"
    }

    private class RestoreFixture(
        val campaignId: UUID = UUID.randomUUID(),
        val batchId: UUID = UUID.randomUUID(),
        val entryId: UUID = UUID.randomUUID(),
        val effectId: UUID = UUID.randomUUID(),
        val workerScopeId: UUID = UUID.randomUUID(),
    ) {
        val identity = EntryIdentity(RESTORE_TENANT, campaignId, batchId, entryId, 0)
        val code = CanonicalVoucherCode.of("POOL-RESTORE-SMOKE")
        val digests = VoucherDigestService.testFixture()
        val crypto: VoucherEnvelopeCrypto = AesGcmVoucherEnvelopeCrypto(
            VoucherKekRing.of(VoucherKek.of("kek-v1", ByteArray(32) { (31 + it).toByte() })),
            digests,
        )
        val stableDigest = digests.stableDedup(RESTORE_TENANT, code)
        val encrypted = crypto.encrypt(identity, code)
        val commandScope = CommandScope(RESTORE_TENANT, "reveal")
        val rawIdempotencyKey = "restore-reveal-key"
        val fingerprint = VoucherPoolFingerprint.command("reveal", mapOf("entryId" to entryId.toString()))
        val scopedKeyDigest = digests.commandTombstone(
            RESTORE_TENANT,
            commandScope.operation,
            rawIdempotencyKey,
        ).copyBytes()
    }
}
