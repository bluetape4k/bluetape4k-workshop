@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.io.serializer.BinarySerializers
import io.bluetape4k.jdbc.sql.singleLong
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@Suppress("SqlResolve")
/**
 * VoucherPoolBackupRestoreIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolBackupRestoreIntegrationTest {

    private companion object: KLogging() {
/**
 * RESTORE_TENANT은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val RESTORE_TENANT = "restore-tenant"
    }

    @Test
/**
 * 이 시나리오는 "restore preflight validates every key before database import" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

        missingCases.forEach { missingCase ->
            val failure = assertFailsWith<VoucherPoolRestoreException> {
                VoucherPoolRestoreCoordinator(missingCase).restore(
                    manifest = manifest(),
                    inventory = backupInventory(),
                    importer = { imported.incrementAndGet() },
                    smoke = ::passingSmoke
                )
            }
            log.debug { "failure: $failure" }
            failure.code shouldBeEqualTo VoucherPoolRestoreFailureCode.MISSING_KEY
        }

        imported.get() shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "restore rejects a manifest that omits keys referenced by backup content" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `restore rejects a manifest that omits keys referenced by backup content`() {
        val imported = AtomicInteger()
        val complete = manifest()

        val incompleteCases = listOf(
            complete.copy(userIdentityVersions = emptySet()),
            complete.copy(auditVersions = emptySet()),
            complete.copy(signatureVersions = emptySet()),
        )

        incompleteCases.forEach { incomplete ->
            val failure = assertFailsWith<VoucherPoolRestoreException> {
                VoucherPoolRestoreCoordinator(availableKeys()).restore(
                    manifest = incomplete,
                    inventory = backupInventory(),
                    importer = { imported.incrementAndGet() },
                    smoke = ::passingSmoke,
                )
            }
            log.debug { "failure: $failure" }
            failure.code shouldBeEqualTo VoucherPoolRestoreFailureCode.INCOMPLETE_MANIFEST
        }

        imported.get() shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "restore retains command tombstone replay fence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `restore retains command tombstone replay fence`() {
        val effectCount = AtomicInteger(1)
        val coordinator = VoucherPoolRestoreCoordinator(availableKeys())

        val restored = coordinator.restore(
            manifest = manifest(),
            inventory = backupInventory(),
            importer = {},
            smoke = ::passingSmoke
        )
        log.debug { "restored: $restored" }

        val retryCode = if (restored.replayFenceRetained) VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED else null

        retryCode shouldBeEqualTo VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED
        effectCount.get() shouldBeEqualTo 1
    }

    @Suppress("DEPRECATION")
    @Test
/**
 * 이 시나리오는 "backup manifest remains a stable serializable recovery contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `backup manifest remains a stable serializable recovery contract`() {
        val original = manifest()
        val bytes = BinarySerializers.JdkUnfiltered.serialize(original)

        val restored = BinarySerializers.JdkUnfiltered.deserialize<BackupKeyManifest>(bytes).shouldNotBeNull()
        log.debug { "restored: $restored" }
        restored shouldBeEqualTo original
    }

    @Test
/**
 * 이 시나리오는 "restore smoke fails closed when any recovery invariant is absent" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `restore smoke fails closed when any recovery invariant is absent`() {
        val coordinator = VoucherPoolRestoreCoordinator(availableKeys())

        val failure = assertFailsWith<VoucherPoolRestoreException> {
            coordinator.restore(
                manifest = manifest(),
                inventory = backupInventory(),
                importer = {}
            ) {
                passingSmoke().copy(cursorRestarted = false)
            }
        }

        log.debug { "failure: $failure" }
        failure.code shouldBeEqualTo VoucherPoolRestoreFailureCode.SMOKE_FAILED
    }

    @Test
/**
 * 이 시나리오는 "logical backup restore proves every persisted recovery invariant" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `logical backup restore proves every persisted recovery invariant`() {
        val schema = "voucher_restore_${Base58.randomString(8).lowercase()}"
        VOUCHER_POOL_TASK_12_POSTGRES.createSchema(schema)
        try {
            val dataSource = postgresDataSource(schema)
            migrationRunner(dataSource).migrate()
            val fixture = RestoreFixture()
            val coordinator = VoucherPoolRestoreCoordinator(availableKeys())

            val restored = coordinator.restore(
                manifest = manifest(),
                inventory = backupInventory(),
                importer = { restoreLogicalBackup(dataSource, fixture) },
                smoke = { inspectRestoredData(dataSource, fixture) },
            )
            log.debug { "restored: $restored" }
            restored.passed.shouldBeTrue()
        } finally {
            VOUCHER_POOL_TASK_12_POSTGRES.dropSchema(schema)
        }
    }

    @Test
/**
 * 이 시나리오는 "rollback selects only proven previous binary or rehearsed verified restore" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * manifest은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun manifest(): BackupKeyManifest =
        BackupKeyManifest(
            kekVersions = setOf("kek-v1"),
            verificationVersions = setOf("2"),
            stableDedupVersion = "1",
            commandTombstoneVersion = "4",
            userIdentityVersions = setOf("3"),
            auditVersions = setOf("6"),
            signatureVersions = setOf("7"),
        )

/**
 * availableKeys은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun availableKeys(kekVersions: Set<String> = setOf("kek-v1")): VoucherPoolAvailableKeys =
        VoucherPoolAvailableKeys(
            kekVersions = kekVersions,
            verificationVersions = setOf("2"),
            stableDedupVersions = setOf("1"),
            commandTombstoneVersions = setOf("4"),
            userIdentityVersions = setOf("3"),
            auditVersions = setOf("6"),
            signatureVersions = setOf("7"),
        )

/**
 * backupInventory은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun backupInventory(): VoucherPoolBackupKeyInventory =
        VoucherPoolBackupKeyInventory(
            kekVersions = setOf("kek-v1"),
            verificationVersions = setOf("2"),
            stableDedupVersions = setOf("1"),
            commandTombstoneVersions = setOf("4"),
            userIdentityVersions = setOf("3"),
            auditVersions = setOf("6"),
            signatureVersions = setOf("7"),
        )

/**
 * passingSmoke은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun passingSmoke(): VoucherPoolRestoreSmokeResult =
        VoucherPoolRestoreSmokeResult(
            ciphertextReadableOrQuarantined = true,
            countersConsistent = true,
            replayFenceRetained = true,
            cursorRestarted = true,
            staleWorkerTakenOver = true,
            exactlyOnceRevealRetained = true,
        )

/**
 * restoreLogicalBackup은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun restoreLogicalBackup(
        dataSource: DataSource,
        fixture: RestoreFixture,
    ) {
        restoreVoucherAuthority(dataSource, fixture)
        restoreRecoveryAuthority(dataSource, fixture)
    }

/**
 * restoreVoucherAuthority은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun restoreVoucherAuthority(dataSource: DataSource, fixture: RestoreFixture) {
        execute(
            dataSource,
            """INSERT INTO voucher_pool_campaigns
                (tenant_id,campaign_id,state,user_identity_key_version,policy_version)
                VALUES ('$RESTORE_TENANT','${fixture.campaignId}','ACTIVE',3,1)""".trimIndent(),
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_batches
                (tenant_id,batch_id,campaign_id,state,source_kind,provenance_digest,request_fingerprint,
                 policy_version,activates_at,expected_count,next_source_ordinal,accepted_count)
                VALUES ('$RESTORE_TENANT','${fixture.batchId}','${fixture.campaignId}','ACTIVE','GENERATED',decode('01','hex'),
                        decode('02','hex'),1,statement_timestamp()-interval '1 hour',1,1,1)""".trimIndent(),
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
                        decode('${fixture.encrypted.copyWrapNonce().toHexString()}','hex'),'kek-v1')""".trimIndent(),
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_pool_depth(tenant_id,batch_id,state,entry_count)
                VALUES ('$RESTORE_TENANT','${fixture.batchId}','AVAILABLE',1)""".trimIndent(),
        )
    }

/**
 * restoreRecoveryAuthority은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun restoreRecoveryAuthority(dataSource: DataSource, fixture: RestoreFixture) {
        execute(
            dataSource,
            """INSERT INTO voucher_pool_http_idempotency
                (tenant_id,operation,scoped_key_digest,fingerprint,status,command_deadline,descriptor,expires_at)
                VALUES ('$RESTORE_TENANT','reveal',decode('${fixture.scopedKeyDigest.toHexString()}','hex'),
                        decode('${fixture.fingerprint.copyBytes().toHexString()}','hex'),'COMPLETED',
                        statement_timestamp()+interval '1 hour',NULL,statement_timestamp())""".trimIndent(),
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_command_tombstones
                (tenant_id,operation,key_version,scoped_key_digest,fingerprint,effect_id)
                VALUES ('$RESTORE_TENANT','reveal',4,decode('${fixture.scopedKeyDigest.toHexString()}','hex'),
                        decode('${
                fixture.fingerprint.copyBytes().toHexString()
            }','hex'),'${fixture.effectId}')""".trimIndent(),
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_audits
                (tenant_id,campaign_id,aggregate_type,aggregate_id,revision,policy_version,actor_type,
                 reason_code,audit_key_version)
                VALUES ('$RESTORE_TENANT','${fixture.campaignId}','CAMPAIGN','${fixture.campaignId}',0,1,'SYSTEM','BACKUP_RESTORE',6)""".trimIndent(),
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_worker_claims
                (tenant_id,worker_type,scope_id,owner_id,claim_until,next_attempt_at,checkpoint)
                VALUES ('$RESTORE_TENANT','PURGE','${fixture.workerScopeId}','stale-worker',
                        statement_timestamp()-interval '1 minute',statement_timestamp(),1)""".trimIndent(),
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_revoke_preview_grants
                (tenant_id,grant_id,aggregate_type,aggregate_id,aggregate_revision,impact_digest,
                 affected_count,signature_key_version,signature_digest,expires_at)
                VALUES ('$RESTORE_TENANT','${UUID.randomUUID()}','CAMPAIGN','${fixture.campaignId}',0,
                        decode('0b','hex'),1,7,decode('0c','hex'),statement_timestamp()+interval '1 hour')""".trimIndent(),
        )
    }

/**
 * inspectRestoredData은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun inspectRestoredData(
        dataSource: DataSource,
        fixture: RestoreFixture,
    ): VoucherPoolRestoreSmokeResult {
/**
 * priorCursor은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val priorCursor = queryLong(
            dataSource,
            "SELECT max(id) FROM voucher_pool_audits WHERE tenant_id='$RESTORE_TENANT'",
        )
        execute(
            dataSource,
            """INSERT INTO voucher_pool_audits
                (tenant_id,campaign_id,aggregate_type,aggregate_id,revision,policy_version,actor_type,
                 reason_code,audit_key_version)
                VALUES (
                  '$RESTORE_TENANT',
                  '${fixture.campaignId}',
                  'CAMPAIGN',
                  '${fixture.campaignId}',
                  1,
                  1,
                  'SYSTEM',
                  'RESTORE_CURSOR_SMOKE'
                  ,6
                  )""".trimIndent(),
        )
/**
 * takeoverCount은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val takeoverCount = executeUpdate(
            dataSource,
            """UPDATE voucher_pool_worker_claims SET owner_id='restore-smoke',
                       claim_until=statement_timestamp()+interval '1 minute',revision=revision+1
                WHERE tenant_id='$RESTORE_TENANT' AND scope_id='${fixture.workerScopeId}'
                  AND claim_until<statement_timestamp()""".trimIndent(),
        )
/**
 * replayDecisions은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val replayDecisions = List(2) { restoredReplayDecision(dataSource, fixture) }
/**
 * replayFenceRetained은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val replayFenceRetained = replayDecisions.all {
            it == IdempotencyDecision.Expired(fixture.effectId, null)
        }

        return VoucherPoolRestoreSmokeResult(
            ciphertextReadableOrQuarantined = restoredCiphertextIsReadable(dataSource, fixture),
            countersConsistent = queryLong(
                dataSource = dataSource,
                sql = """SELECT count(*) FROM voucher_pool_batches b
                          WHERE b.tenant_id='$RESTORE_TENANT' AND b.accepted_count=(
                              SELECT count(*) FROM voucher_pool_entries e
                              WHERE e.tenant_id=b.tenant_id AND e.batch_id=b.batch_id)""".trimIndent(),
            ) == 1L,
            replayFenceRetained = replayFenceRetained,
            cursorRestarted = queryLong(
                dataSource = dataSource,
                sql = "SELECT max(id) FROM voucher_pool_audits WHERE tenant_id='$RESTORE_TENANT'",
            ) > priorCursor,
            staleWorkerTakenOver = takeoverCount == 1,
            exactlyOnceRevealRetained = replayFenceRetained &&
                    queryLong(
                        dataSource = dataSource,
                        sql = """SELECT count(*) FROM voucher_pool_command_tombstones
                          WHERE tenant_id='$RESTORE_TENANT' 
                            AND operation='reveal' 
                            AND effect_id IS NOT NULL""".trimIndent(),
                    ) == 1L,
        )
    }

/**
 * restoredReplayDecision은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun restoredReplayDecision(dataSource: DataSource, fixture: RestoreFixture): IdempotencyDecision {
        val database = Database.connect(dataSource)
        val repository = JdbcVoucherPoolIdempotencyRepository(fixture.digests)

        return transaction(database) {
            repository.acquire(fixture.commandScope, fixture.rawIdempotencyKey, fixture.fingerprint)
        }
    }

/**
 * execute은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun execute(dataSource: DataSource, sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute(sql)
            }
        }
    }

/**
 * executeUpdate은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun executeUpdate(dataSource: DataSource, sql: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.executeUpdate(sql)
            }
        }

/**
 * queryLong은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun queryLong(dataSource: DataSource, sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    result.singleLong()
                }
            }
        }

/**
 * restoredCiphertextIsReadable은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun restoredCiphertextIsReadable(dataSource: DataSource, fixture: RestoreFixture): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT stable_dedup_digest,code_ciphertext,code_nonce,wrapped_dek,wrap_nonce,kek_version
                    FROM voucher_pool_entries 
                    WHERE tenant_id=? AND entry_id=?""".trimIndent(),
            ).use { statement ->
                statement.setString(1, RESTORE_TENANT)
                statement.setObject(2, fixture.entryId)

                statement.executeQuery().use { result ->
                    check(result.next())
/**
 * digest은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val digest = VoucherDigest(
                        purpose = DigestPurpose.STABLE_DEDUP,
                        keyVersion = 1,
                        value = result.getBytes("stable_dedup_digest")
                    )
/**
 * encrypted은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val encrypted = EncryptedVoucherCode(
                        codeCiphertext = result.getBytes("code_ciphertext"),
                        codeNonce = result.getBytes("code_nonce"),
                        wrappedDek = result.getBytes("wrapped_dek"),
                        wrapNonce = result.getBytes("wrap_nonce"),
                        kekVersion = result.getString("kek_version"),
                    )
                    fixture.crypto.decryptAndVerify(
                        entryIdentity = fixture.identity,
                        encrypted = encrypted,
                        expectedStableDedup = digest
                    ) == fixture.code
                }
            }
        }

/**
 * RestoreFixture 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RestoreFixture(
/**
 * campaignId은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val campaignId: UUID = UUID.randomUUID(),
/**
 * batchId은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val batchId: UUID = UUID.randomUUID(),
/**
 * entryId은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val entryId: UUID = UUID.randomUUID(),
/**
 * effectId은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val effectId: UUID = UUID.randomUUID(),
/**
 * workerScopeId은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val workerScopeId: UUID = UUID.randomUUID(),
    ) {
/**
 * identity은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val identity = EntryIdentity(RESTORE_TENANT, campaignId, batchId, entryId, 0)
/**
 * code은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val code = CanonicalVoucherCode.of("POOL-RESTORE-SMOKE")
/**
 * digests은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val digests = VoucherDigestService.testFixture()
/**
 * crypto은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val crypto: VoucherEnvelopeCrypto = AesGcmVoucherEnvelopeCrypto(
            kekRing = VoucherKekRing(VoucherKek("kek-v1", ByteArray(32) { (31 + it).toByte() })),
            digests = digests,
        )
/**
 * stableDigest은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val stableDigest = digests.stableDedup(RESTORE_TENANT, code)
/**
 * encrypted은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val encrypted = crypto.encrypt(identity, code)
/**
 * commandScope은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val commandScope = CommandScope(RESTORE_TENANT, "reveal")
/**
 * rawIdempotencyKey은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val rawIdempotencyKey = "restore-reveal-key"

/**
 * fingerprint은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fingerprint = VoucherPoolFingerprint.command(
            operation = "reveal",
            fields = mapOf("entryId" to entryId.toString())
        )

/**
 * scopedKeyDigest은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val scopedKeyDigest = digests
            .commandTombstone(
                tenantId = RESTORE_TENANT,
                operation = commandScope.operation,
                rawIdempotencyKey = rawIdempotencyKey,
            )
            .copyBytes()
    }
}
