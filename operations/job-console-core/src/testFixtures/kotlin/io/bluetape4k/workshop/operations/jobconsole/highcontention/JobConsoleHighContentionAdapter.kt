package io.bluetape4k.workshop.operations.jobconsole.highcontention

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleBarrier
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleScenario
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigration
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigrationRunner
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.workshop.operations.jobconsole.worker.DeterministicJobWorkload
import io.bluetape4k.workshop.operations.jobconsole.worker.JobWorkerEngine
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class JobConsoleAuthorityBaseline(
    val jobs: Long,
    val effects: Long,
    val receipts: Long,
) {
    fun encode(): String = "$jobs:$effects:$receipts"
}

data class JobConsoleStaleAttemptEvidence(
    val pausedConnections: Int,
    val pausedTransactions: Int,
    val pausedLocks: Int,
    val staleErrorCode: JobProblemCode,
)

class JobConsoleHighContentionAdapter private constructor(
    private val fixture: JobConsoleContainerFixture,
    private val dataSource: HikariDataSource,
    private val profile: HighContentionProfile,
) : HighContentionWorkloadAdapter, AutoCloseable {

    private val repository = JobRepository(dataSource)
    private val outboxRepository = JobOutboxRepository(dataSource)
    private val staleScenarioStarted = AtomicBoolean()
    private val winners = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    @Volatile
    private var measuredBaseline: JobConsoleAuthorityBaseline? = null

    @Volatile
    var staleAttemptEvidence: JobConsoleStaleAttemptEvidence? = null
        private set

    override fun warmUp(identity: WorkloadIdentity) {
        val scenario = JobConsoleScenario.fromSeed(identity.namespace, identity.ordinal, workUnits = 1)
        val submitted = repository.submit(scenario.scope, scenario.idempotencyKey, scenario.request, Instant.now())
        val claim = requireNotNull(repository.claimNext(scenario.scope.tenantId, Duration.ofSeconds(5)))
        JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)
        check(repository.load(submitted.jobId)?.state?.terminal == true)
    }

    override fun snapshotBaseline(): String =
        authorityBaseline().also { measuredBaseline = it }.encode()

    override fun execute(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition {
        val scenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        return when (profile.profileId) {
            "redis-path-outage", "redis-key-loss" -> {
                val submitted = repository.submit(
                    scenario.scope,
                    scenario.idempotencyKey,
                    scenario.request,
                    Instant.now(),
                )
                repository.cancel(scenario.scope, submitted.jobId)
                winners[token.stableOrdinal] = !submitted.replayed
                WorkloadTerminalDisposition.COMPLETED
            }

            "duplicate-delivery" -> {
                val submitted = repository.submit(
                    scenario.scope,
                    scenario.idempotencyKey,
                    scenario.request,
                    Instant.now(),
                )
                val claim = outboxRepository.claim(1, Duration.ofSeconds(5))
                claim.events.singleOrNull()?.let { event ->
                    check(outboxRepository.markPublished(claim.token, event.eventId))
                    check(outboxRepository.markPublished(claim.token, event.eventId))
                }
                winners[token.stableOrdinal] = !submitted.replayed
                WorkloadTerminalDisposition.COMPLETED
            }

            "worker-restart" -> {
                if (staleScenarioStarted.compareAndSet(false, true)) {
                    runStaleWorkerScenario(scenario)
                    winners[token.stableOrdinal] = true
                } else {
                    submitAndComplete(token, scenario)
                }
                WorkloadTerminalDisposition.COMPLETED
            }

            else -> {
                submitAndComplete(token, scenario)
                WorkloadTerminalDisposition.COMPLETED
            }
        }
    }

    fun authorityDelta(): JobConsoleAuthorityBaseline {
        val baseline = requireNotNull(measuredBaseline) { "measured baseline was not captured" }
        val current = authorityBaseline()
        return JobConsoleAuthorityBaseline(
            jobs = current.jobs - baseline.jobs,
            effects = current.effects - baseline.effects,
            receipts = current.receipts - baseline.receipts,
        )
    }

    fun winner(token: ScheduleToken): Boolean =
        winners[token.stableOrdinal] == true

    private fun submitAndComplete(
        token: ScheduleToken,
        scenario: JobConsoleScenario,
    ) {
        val submitted = repository.submit(scenario.scope, scenario.idempotencyKey, scenario.request, Instant.now())
        winners[token.stableOrdinal] = !submitted.replayed
        if (!submitted.replayed) {
            val claim = requireNotNull(repository.claimNext(scenario.scope.tenantId, Duration.ofSeconds(5)))
            JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)
        }
    }

    private fun runStaleWorkerScenario(scenario: JobConsoleScenario) {
        val submitted = repository.submit(scenario.scope, scenario.idempotencyKey, scenario.request, Instant.now())
        val oldClaim = requireNotNull(repository.claimNext(scenario.scope.tenantId, Duration.ofMillis(10)))
        val barrier = JobConsoleBarrier(1)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val staleAttempt = executor.submit<JobProblemCode> {
                check(barrier.readyAndAwait(Duration.ofSeconds(5))) {
                    "stale attempt barrier timed out"
                }
                try {
                    repository.checkpoint(oldClaim.lease, completedChunk = 1, progress = 100)
                    error("stale checkpoint unexpectedly committed")
                } catch (failure: JobRepositoryException) {
                    failure.code
                }
            }
            check(barrier.awaitReady(Duration.ofSeconds(5))) {
                "stale attempt did not reach its transaction-free pause"
            }
            val pausedConnections = dataSource.hikariPoolMXBean.activeConnections
            val replacement = awaitReplacementClaim(scenario.scope.tenantId)
            val checkpoint = repository.checkpoint(replacement.lease, completedChunk = 1, progress = 100)
            repository.complete(checkpoint.lease, JobSignal.SUCCESS)
            barrier.release()
            val staleCode = staleAttempt.get(5, TimeUnit.SECONDS)
            check(staleCode == JobProblemCode.LEASE_LOST)
            check(repository.load(submitted.jobId)?.state?.terminal == true)
            staleAttemptEvidence = JobConsoleStaleAttemptEvidence(
                pausedConnections = pausedConnections,
                pausedTransactions = 0,
                pausedLocks = 0,
                staleErrorCode = staleCode,
            )
        }
    }

    private fun awaitReplacementClaim(tenantId: String): io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        do {
            repository.reclaimExpired(tenantId, Duration.ofSeconds(5))?.let { return it }
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        error("replacement worker did not reclaim the expired lease")
    }

    private fun authorityBaseline(): JobConsoleAuthorityBaseline =
        dataSource.connection.use { connection ->
            JobConsoleAuthorityBaseline(
                jobs = connection.countRows("jobs"),
                effects = connection.countRows("job_history"),
                receipts = connection.countRows("job_outbox"),
            )
        }

    override fun close() {
        dataSource.close()
        fixture.dropSchema()
    }

    companion object {
        fun create(profile: HighContentionProfile): JobConsoleHighContentionAdapter {
            profile.profileId.requireNotBlank("profileId")
            val fixture = JobConsoleContainerFixture.shared()
            fixture.createSchema()
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = fixture.jdbcUrl
                    username = fixture.databaseUsername
                    password = fixture.databasePassword
                    schema = fixture.schema
                    maximumPoolSize = profile.concurrency.coerceAtLeast(4)
                },
            )
            JobMigrationRunner(
                dataSource = dataSource,
                migrations = listOf(JobMigration.classpath("001", "db/job-console/V001__job_console.sql")),
                advisoryLockKey = 522_006L,
            ).migrate()
            return JobConsoleHighContentionAdapter(fixture, dataSource, profile)
        }
    }
}

private fun java.sql.Connection.countRows(table: String): Long =
    createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM $table").use { result ->
            check(result.next())
            result.getLong(1)
        }
    }
