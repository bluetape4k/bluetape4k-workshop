@file:Suppress("MagicNumber", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.worker

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.core.io.ClassPathResource
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
/**
 * VoucherPoolWorkerRepositoryTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolWorkerRepositoryTest {

    companion object: KLogging() {
/**
 * postgres은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

/**
 * tenant은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val tenant = "tenant-worker-claim"
/**
 * voucherSchema은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val voucherSchema = "voucher_worker_${Base58.randomString(8).lowercase()}"
/**
 * scopeId은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val scopeId = UUID.randomUUID()
/**
 * dataSource은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val dataSource = postgres.getHikariDataSource {
        schema = voucherSchema
    }
/**
 * repository은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var repository: JdbcVoucherPoolWorkerRepository

    @BeforeAll
    fun migrate() {
        adminConnection().use {
            it.createStatement().execute("CREATE SCHEMA $voucherSchema")
        }
        VoucherPoolMigrationRunner(
            dataSource = dataSource,
            migration = VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            advisoryLockKey = 537_008L,
        ).migrate()

        val executor = VoucherPoolJdbcExecutor(
            gate = DatabasePermitGate.default(32),
            transactionManager = SpringTransactionManager(dataSource, DatabaseConfig {}, false),
        )
        repository = JdbcVoucherPoolWorkerRepository(executor)
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
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("TRUNCATE voucher_pool_worker_claims")
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "duplicate claim is rejected until the lease expires then stale owner cannot checkpoint"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `duplicate claim is rejected until the lease expires then stale owner cannot checkpoint`() {
        val original = repository.claim(tenant, WorkerKind.BATCH_REVOKE, scopeId, "owner-a").shouldNotBeNull()
        repository.claim(tenant, WorkerKind.BATCH_REVOKE, scopeId, "owner-b").shouldBeNull()

        expireLease()

        val takeover = checkNotNull(repository.claim(tenant, WorkerKind.BATCH_REVOKE, scopeId, "owner-b"))
        takeover.owner shouldBeEqualTo "owner-b"
        takeover.revision shouldBeGreaterThan original.revision

        assertFailsWith<StaleWorkerClaimException> {
            repository.checkpoint(original, 10)
        }
    }

    @Test
/**
 * 이 시나리오는 "successful checkpoint renews lease and stale finalize is rejected" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `successful checkpoint renews lease and stale finalize is rejected`() {
        val original = repository.claim(tenant, WorkerKind.RESERVATION_EXPIRY, scopeId, "owner").shouldNotBeNull()
        val renewed = repository.checkpoint(original, 42)

        renewed.cursor shouldBeEqualTo 42L
        renewed.checkpoint shouldBeEqualTo 1L
        renewed.claimUntil shouldBeGreaterThan original.claimUntil

        assertFailsWith<StaleWorkerClaimException> {
            repository.finalize(original)
        }

        val finalized = repository.finalize(renewed)
        finalized.owner.shouldBeNull()
        finalized.cursor shouldBeEqualTo 0L
    }

    @Test
/**
 * 이 시나리오는 "failed chunks back off then poison at the bounded fifth attempt" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `failed chunks back off then poison at the bounded fifth attempt`() {
        var claim = repository.claim(tenant, WorkerKind.RECONCILIATION, scopeId, "owner").shouldNotBeNull()
        val backoffs = mutableListOf<Long>()

        repeat(5) { attempt ->
            val failure = repository.fail(claim, "SIMULATED_FAILURE")
            backoffs += failure.backoffSeconds
            if (attempt < 4) {
                makeDue()
                claim = repository.claim(tenant, WorkerKind.RECONCILIATION, scopeId, "owner").shouldNotBeNull()
            }
        }

        backoffs shouldBeEqualTo listOf(1L, 2L, 4L, 8L, 16L)

        val snapshot = repository.snapshot(tenant, WorkerKind.RECONCILIATION, scopeId).shouldNotBeNull()
        snapshot.attempt shouldBeEqualTo 5
        snapshot.poisonReason shouldBeEqualTo "SIMULATED_FAILURE"
        snapshot.state shouldBeEqualTo WorkerClaimState.POISONED
        snapshot.nextAction shouldBeEqualTo "OPERATOR_REVIEW_REQUIRED"

        repository.claim(tenant, WorkerKind.RECONCILIATION, scopeId, "other").shouldBeNull()
    }

    @Test
/**
 * 이 시나리오는 "default lease and run deadline are bounded and an expired run cannot checkpoint" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `default lease and run deadline are bounded and an expired run cannot checkpoint`() {
        WorkerPolicy().lease shouldBeEqualTo 15.seconds()
        WorkerPolicy().runDeadline shouldBeEqualTo 30.seconds()

        val claim = repository.claim(tenant, WorkerKind.RESERVATION_EXPIRY, scopeId, "deadline-owner").shouldNotBeNull()
        claim.runDeadline shouldBeGreaterThan claim.claimUntil

        assertFailsWith<StaleWorkerClaimException> {
            repository.checkpoint(claim.copy(runDeadline = Instant.EPOCH), 1)
        }
        repository.release(claim).state shouldBeEqualTo WorkerClaimState.IDLE
    }

    @Test
/**
 * 이 시나리오는 "cancellation release leaves the claim immediately reclaimable" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `cancellation release leaves the claim immediately reclaimable`() {
        val claim = repository.claim(tenant, WorkerKind.ALLOCATION_EXPIRY, scopeId, "owner-a").shouldNotBeNull()
        val checkpoint = repository.checkpoint(claim, 17)
        val released = repository.release(checkpoint)

        log.debug { "released: $released" }
        released.owner.shouldBeNull()
        released.cursor shouldBeEqualTo 17L

        repository.claim(tenant, WorkerKind.ALLOCATION_EXPIRY, scopeId, "owner-b")!!.cursor shouldBeEqualTo 17L
    }

    @Test
/**
 * 이 시나리오는 "runnable scan includes released and stale claims but excludes completed claims" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `runnable scan includes released and stale claims but excludes completed claims`() {
        val releasedScope = UUID.randomUUID()
        val staleScope = UUID.randomUUID()
        val completedScope = UUID.randomUUID()

        repository.release(
            repository.claim(tenant, WorkerKind.BATCH_REVOKE, releasedScope, "released-owner")!!,
        )
        repository.claim(tenant, WorkerKind.RECONCILIATION, staleScope, "stale-owner")

        updateClaim("claim_until=transaction_timestamp()-interval '1 second'", staleScope)
        repository.finalize(
            repository.claim(tenant, WorkerKind.BATCH_EXPIRY, completedScope, "completed-owner")!!,
        )

        repository.findRunnable(10).toSet() shouldBeEqualTo setOf(
            WorkerClaimCandidate(tenant, WorkerKind.BATCH_REVOKE, releasedScope),
            WorkerClaimCandidate(tenant, WorkerKind.RECONCILIATION, staleScope),
        )
    }

/**
 * expireLease은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun expireLease() {
        updateClaim("claim_until=transaction_timestamp()-interval '1 second'")
    }

/**
 * makeDue은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun makeDue() {
        updateClaim("next_attempt_at=transaction_timestamp()-interval '1 second'")
    }

/**
 * updateClaim은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun updateClaim(setClause: String, targetScopeId: UUID = scopeId) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                        UPDATE voucher_pool_worker_claims 
                           SET $setClause 
                         WHERE tenant_id='$tenant' 
                           AND scope_id='$targetScopeId'
                        """.trimIndent()
                )
            }
        }
    }

/**
 * adminConnection은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun adminConnection(): Connection =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )
}
