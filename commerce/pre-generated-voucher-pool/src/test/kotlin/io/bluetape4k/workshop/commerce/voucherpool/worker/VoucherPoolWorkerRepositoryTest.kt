@file:Suppress("MagicNumber")

package io.bluetape4k.workshop.commerce.voucherpool.worker

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
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
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.io.ClassPathResource
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolWorkerRepositoryTest {
    private val tenant = "tenant-worker-claim"
    private val schema = "voucher_worker_${Base58.randomString(8).lowercase()}"
    private val scopeId = UUID.randomUUID()
    private val dataSource = PGSimpleDataSource().apply {
        setURL(postgres.jdbcUrl)
        user = postgres.username ?: PostgreSQLServer.USERNAME
        password = postgres.password ?: PostgreSQLServer.PASSWORD
        currentSchema = schema
    }
    private lateinit var repository: JdbcVoucherPoolWorkerRepository

    @BeforeAll
    fun migrate() {
        adminConnection().use { it.createStatement().execute("CREATE SCHEMA $schema") }
        VoucherPoolMigrationRunner(
            dataSource,
            VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            537_008L,
        ).migrate()
        val executor = VoucherPoolJdbcExecutor(
            DatabasePermitGate.default(32),
            SpringTransactionManager(dataSource, DatabaseConfig {}, false),
        )
        repository = JdbcVoucherPoolWorkerRepository(executor)
    }

    @AfterAll
    fun cleanup() {
        adminConnection().use { it.createStatement().execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    @BeforeEach
    fun reset() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("TRUNCATE voucher_pool_worker_claims") }
        }
    }

    @Test
    fun `duplicate claim is rejected until the lease expires then stale owner cannot checkpoint`() {
        val original = checkNotNull(repository.claim(tenant, WorkerKind.BATCH_REVOKE, scopeId, "owner-a"))
        repository.claim(tenant, WorkerKind.BATCH_REVOKE, scopeId, "owner-b").shouldBeNull()

        expireLease()
        val takeover = checkNotNull(repository.claim(tenant, WorkerKind.BATCH_REVOKE, scopeId, "owner-b"))
        takeover.owner shouldBeEqualTo "owner-b"
        (takeover.revision > original.revision).shouldBeTrue()
        assertFailsWith<StaleWorkerClaimException> { repository.checkpoint(original, 10) }
    }

    @Test
    fun `successful checkpoint renews lease and stale finalize is rejected`() {
        val original = checkNotNull(repository.claim(tenant, WorkerKind.RESERVATION_EXPIRY, scopeId, "owner"))
        val renewed = repository.checkpoint(original, 42)

        renewed.cursor shouldBeEqualTo 42L
        renewed.checkpoint shouldBeEqualTo 1L
        (renewed.claimUntil > original.claimUntil).shouldBeTrue()
        assertFailsWith<StaleWorkerClaimException> { repository.finalize(original) }
        val finalized = repository.finalize(renewed)
        finalized.owner.shouldBeNull()
        finalized.cursor shouldBeEqualTo 0L
    }

    @Test
    fun `failed chunks back off then poison at the bounded fifth attempt`() {
        var claim = checkNotNull(repository.claim(tenant, WorkerKind.RECONCILIATION, scopeId, "owner"))
        val backoffs = mutableListOf<Long>()

        repeat(5) { attempt ->
            val failure = repository.fail(claim, "SIMULATED_FAILURE")
            backoffs += failure.backoffSeconds
            if (attempt < 4) {
                makeDue()
                claim = checkNotNull(repository.claim(tenant, WorkerKind.RECONCILIATION, scopeId, "owner"))
            }
        }

        backoffs shouldBeEqualTo listOf(1L, 2L, 4L, 8L, 16L)
        val snapshot = checkNotNull(repository.snapshot(tenant, WorkerKind.RECONCILIATION, scopeId))
        snapshot.attempt shouldBeEqualTo 5
        snapshot.poisonReason shouldBeEqualTo "SIMULATED_FAILURE"
        snapshot.state shouldBeEqualTo WorkerClaimState.POISONED
        snapshot.nextAction shouldBeEqualTo "OPERATOR_REVIEW_REQUIRED"
        repository.claim(tenant, WorkerKind.RECONCILIATION, scopeId, "other").shouldBeNull()
    }

    @Test
    fun `default lease and run deadline are bounded and an expired run cannot checkpoint`() {
        WorkerPolicy().lease shouldBeEqualTo Duration.ofSeconds(15)
        WorkerPolicy().runDeadline shouldBeEqualTo Duration.ofSeconds(30)
        val claim = checkNotNull(repository.claim(tenant, WorkerKind.RESERVATION_EXPIRY, scopeId, "deadline-owner"))
        (claim.runDeadline > claim.claimUntil).shouldBeTrue()

        assertFailsWith<StaleWorkerClaimException> {
            repository.checkpoint(claim.copy(runDeadline = Instant.EPOCH), 1)
        }
        repository.release(claim).state shouldBeEqualTo WorkerClaimState.IDLE
    }

    @Test
    fun `cancellation release leaves the claim immediately reclaimable`() {
        val claim = checkNotNull(repository.claim(tenant, WorkerKind.ALLOCATION_EXPIRY, scopeId, "owner-a"))
        val checkpoint = repository.checkpoint(claim, 17)
        val released = repository.release(checkpoint)
        released.owner.shouldBeNull()
        released.cursor shouldBeEqualTo 17L

        checkNotNull(
            repository.claim(tenant, WorkerKind.ALLOCATION_EXPIRY, scopeId, "owner-b"),
        ).cursor shouldBeEqualTo 17L
    }

    @Test
    fun `runnable scan includes released and stale claims but excludes completed claims`() {
        val releasedScope = UUID.randomUUID()
        val staleScope = UUID.randomUUID()
        val completedScope = UUID.randomUUID()
        repository.release(
            checkNotNull(repository.claim(tenant, WorkerKind.BATCH_REVOKE, releasedScope, "released-owner")),
        )
        repository.claim(tenant, WorkerKind.RECONCILIATION, staleScope, "stale-owner")
        updateClaim("claim_until=transaction_timestamp()-interval '1 second'", staleScope)
        repository.finalize(
            checkNotNull(repository.claim(tenant, WorkerKind.BATCH_EXPIRY, completedScope, "completed-owner")),
        )

        repository.findRunnable(10).toSet() shouldBeEqualTo setOf(
            WorkerClaimCandidate(tenant, WorkerKind.BATCH_REVOKE, releasedScope),
            WorkerClaimCandidate(tenant, WorkerKind.RECONCILIATION, staleScope),
        )
    }

    private fun expireLease() = updateClaim("claim_until=transaction_timestamp()-interval '1 second'")

    private fun makeDue() = updateClaim("next_attempt_at=transaction_timestamp()-interval '1 second'")

    private fun updateClaim(setClause: String, targetScopeId: UUID = scopeId) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE voucher_pool_worker_claims SET $setClause " +
                        "WHERE tenant_id='$tenant' AND scope_id='$targetScopeId'",
                )
            }
        }
    }

    private fun adminConnection() = DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username ?: PostgreSQLServer.USERNAME,
        postgres.password ?: PostgreSQLServer.PASSWORD,
    )

    companion object {
        private val postgres = PostgreSQLServer.Launcher.postgres
    }
}
