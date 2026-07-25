package io.bluetape4k.workshop.operations.jobconsole.highcontention

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    val lateEffectCount: Long,
    val lateReceiptCount: Long,
)

class JobConsoleHighContentionAdapter private constructor(
    private val fixture: JobConsoleContainerFixture,
    private val dataSource: HikariDataSource,
    private val profile: HighContentionProfile,
) : JobConsoleLiveProfileAdapter {

    private val repository = JobRepository(dataSource)
    private val outboxRepository = JobOutboxRepository(dataSource)
    private val staleScenarioStarted = AtomicBoolean()
    private val injected = AtomicBoolean()
    private val ephemeralSignalAvailable = AtomicBoolean(true)
    private val durableCancelsDuringOutage = AtomicInteger()
    private val durableCancelsAfterKeyLoss = AtomicInteger()
    private val winners = ConcurrentHashMap<Int, Boolean>()
    private val jobIdsByIdentity = ConcurrentHashMap<Int, UUID>()
    private val deliveredEventIds = ConcurrentHashMap.newKeySet<UUID>()
    private val ownedEphemeralKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var measuredBaseline: JobConsoleAuthorityBaseline? = null

    @Volatile
    private var deletedOwnedKeyCount: Int = 0

    @Volatile
    var staleAttemptEvidence: JobConsoleStaleAttemptEvidence? = null
        private set

    override fun warmUp(identity: WorkloadIdentity) {
        val scenario = JobConsoleScenario.fromSeed(identity.namespace, identity.ordinal, workUnits = 1)
        val submitted = repository.submit(scenario.scope, scenario.idempotencyKey, scenario.request, Instant.now())
        val claim = repository.claimNext(scenario.scope.tenantId, Duration.ofSeconds(5))
            .requireNotNull("warm-up claim")
        JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)
        check(repository.load(submitted.jobId)?.state?.terminal == true)
    }

    override fun snapshotBaseline(): String =
        authorityBaseline().also {
            measuredBaseline = it
            if (profile.failure.kind == FailureKind.REDIS_KEY_LOSS) {
                repeat(profile.operationCount) { ordinal ->
                    ownedEphemeralKeys += "hc:${profile.seed}:signal:$ordinal"
                }
            }
        }.encode()

    override fun execute(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition {
        val scenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        return when (profile.failure.kind) {
            FailureKind.REDIS_PATH_OUTAGE,
            FailureKind.REDIS_KEY_LOSS,
            -> submitAndCancel(token, identity, scenario)

            FailureKind.DUPLICATE_DELIVERY -> deliverStableEventTwice(token, identity, scenario)

            FailureKind.SLOW_PROVIDER,
            FailureKind.WORKER_RESTART,
            -> {
                if (staleScenarioStarted.compareAndSet(false, true)) {
                    val jobId = runStaleWorkerScenario(scenario)
                    jobIdsByIdentity.putIfAbsent(identity.ordinal, jobId)
                    winners[token.stableOrdinal] = true
                } else {
                    submitAndComplete(token, identity, scenario)
                }
                WorkloadTerminalDisposition.COMPLETED
            }

            else -> {
                submitAndComplete(token, identity, scenario)
                WorkloadTerminalDisposition.COMPLETED
            }
        }
    }

    override fun injectDeclaredFailure() {
        check(injected.compareAndSet(false, true)) {
            "declared Job core failure was injected more than once"
        }
        when (profile.failure.kind) {
            FailureKind.REDIS_PATH_OUTAGE -> {
                ephemeralSignalAvailable.set(false)
                try {
                    HighContentionAwait.condition(
                        timeout = Duration.ofMillis(profile.injectionDeadlineMs),
                        pollInterval = Duration.ofMillis(10),
                        description = "durable cancel was not observed while the core signal path was unavailable",
                    ) {
                        durableCancelsDuringOutage.get() > 0
                    }
                } finally {
                    ephemeralSignalAvailable.set(true)
                }
            }

            FailureKind.REDIS_KEY_LOSS -> {
                deletedOwnedKeyCount = ownedEphemeralKeys.size
                ownedEphemeralKeys.clear()
                check(deletedOwnedKeyCount == profile.operationCount) {
                    "core owned signal key deletion did not match the bounded namespace"
                }
                HighContentionAwait.condition(
                    timeout = Duration.ofMillis(profile.injectionDeadlineMs),
                    pollInterval = Duration.ofMillis(10),
                    description = "durable cancel was not observed after core signal key loss",
                ) {
                    durableCancelsAfterKeyLoss.get() > 0
                }
            }

            else -> Unit
        }
    }

    override fun authorityDelta(): JobConsoleAuthorityBaseline {
        val baseline = measuredBaseline.requireNotNull("measured baseline")
        val current = authorityBaseline()
        return JobConsoleAuthorityBaseline(
            jobs = current.jobs - baseline.jobs,
            effects = current.effects - baseline.effects,
            receipts = current.receipts - baseline.receipts,
        )
    }

    override fun winner(token: ScheduleToken): Boolean =
        winners[token.stableOrdinal] == true

    override fun profileEvidence(): String =
        when (profile.failure.kind) {
            FailureKind.NONE ->
                "coreBoundary=postgresql,queueIdentities=${jobIdsByIdentity.size}"

            FailureKind.DUPLICATE_SUBMISSION ->
                "coreBoundary=postgresql,idempotentIdentities=${jobIdsByIdentity.size}"

            FailureKind.REDIS_PATH_OUTAGE ->
                "coreSignalFixture=deterministic-fake," +
                    "durableCancelsDuringOutage=${durableCancelsDuringOutage.get()}"

            FailureKind.REDIS_KEY_LOSS ->
                "coreSignalFixture=deterministic-fake,deletedOwnedKeys=$deletedOwnedKeyCount," +
                    "durableCancelsAfterKeyLoss=${durableCancelsAfterKeyLoss.get()}"

            FailureKind.SLOW_PROVIDER,
            FailureKind.WORKER_RESTART,
            -> staleAttemptEvidence?.let {
                "staleCode=${it.staleErrorCode},pausedConnections=${it.pausedConnections}," +
                    "lateEffects=${it.lateEffectCount},lateReceipts=${it.lateReceiptCount}"
            }.orEmpty()

            FailureKind.DUPLICATE_DELIVERY ->
                "duplicateStableEvents=${deliveredEventIds.size}"
        }

    override fun assertProfileInvariants(delta: JobConsoleAuthorityBaseline) {
        check(injected.get()) { "declared Job core failure was not observed" }
        check(delta.jobs > 0L) { "measured workload did not create durable Job authority" }
        check(delta.effects > 0L) { "measured workload did not create durable Job history" }
        check(delta.receipts > 0L) { "measured workload did not create durable Job outbox receipts" }
        when (profile.failure.kind) {
            FailureKind.NONE ->
                check(jobIdsByIdentity.size == profile.operationCount)

            FailureKind.DUPLICATE_SUBMISSION ->
                check(jobIdsByIdentity.size == profile.contentionShape.identityCount)

            FailureKind.REDIS_PATH_OUTAGE -> {
                check(ephemeralSignalAvailable.get())
                check(durableCancelsDuringOutage.get() > 0)
            }

            FailureKind.REDIS_KEY_LOSS -> {
                check(deletedOwnedKeyCount == profile.operationCount)
                check(ownedEphemeralKeys.isEmpty())
                check(durableCancelsAfterKeyLoss.get() > 0)
            }

            FailureKind.SLOW_PROVIDER,
            FailureKind.WORKER_RESTART,
            -> assertStaleAttemptEvidence()

            FailureKind.DUPLICATE_DELIVERY ->
                check(deliveredEventIds.isNotEmpty()) {
                    "duplicate delivery did not replay a stable outbox event"
                }
        }
    }

    private fun submitAndCancel(
        token: ScheduleToken,
        identity: WorkloadIdentity,
        scenario: JobConsoleScenario,
    ): WorkloadTerminalDisposition {
        val submitted = repository.submit(
            scenario.scope,
            scenario.idempotencyKey,
            scenario.request,
            Instant.now(),
        )
        recordIdentity(identity, submitted.jobId)
        repository.cancel(scenario.scope, submitted.jobId)
        winners[token.stableOrdinal] = !submitted.replayed
        if (!ephemeralSignalAvailable.get()) {
            durableCancelsDuringOutage.incrementAndGet()
        }
        if (deletedOwnedKeyCount > 0) {
            durableCancelsAfterKeyLoss.incrementAndGet()
        }
        return WorkloadTerminalDisposition.COMPLETED
    }

    private fun deliverStableEventTwice(
        token: ScheduleToken,
        identity: WorkloadIdentity,
        scenario: JobConsoleScenario,
    ): WorkloadTerminalDisposition {
        val submitted = repository.submit(
            scenario.scope,
            scenario.idempotencyKey,
            scenario.request,
            Instant.now(),
        )
        recordIdentity(identity, submitted.jobId)
        val claim = outboxRepository.claim(1, Duration.ofSeconds(5))
        claim.events.singleOrNull()?.let { event ->
            check(outboxRepository.markPublished(claim.token, event.eventId))
            check(outboxRepository.markPublished(claim.token, event.eventId))
            deliveredEventIds += event.eventId
        }
        winners[token.stableOrdinal] = !submitted.replayed
        return WorkloadTerminalDisposition.COMPLETED
    }

    private fun submitAndComplete(
        token: ScheduleToken,
        identity: WorkloadIdentity,
        scenario: JobConsoleScenario,
    ) {
        val submitted = repository.submit(scenario.scope, scenario.idempotencyKey, scenario.request, Instant.now())
        recordIdentity(identity, submitted.jobId)
        winners[token.stableOrdinal] = !submitted.replayed
        if (!submitted.replayed) {
            val claim = repository.claimNext(scenario.scope.tenantId, Duration.ofSeconds(5))
                .requireNotNull("measured claim")
            JobWorkerEngine(repository, DeterministicJobWorkload()).run(claim)
        }
    }

    private fun recordIdentity(
        identity: WorkloadIdentity,
        jobId: UUID,
    ) {
        val existing = jobIdsByIdentity.putIfAbsent(identity.ordinal, jobId)
        check(existing == null || existing == jobId) {
            "stable Job identity resolved to different jobs"
        }
    }

    private fun runStaleWorkerScenario(scenario: JobConsoleScenario): UUID {
        val submitted = repository.submit(scenario.scope, scenario.idempotencyKey, scenario.request, Instant.now())
        val oldClaim = repository.claimNext(scenario.scope.tenantId, Duration.ofMillis(10))
            .requireNotNull("old worker claim")
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
            val beforeRelease = authorityBaseline(submitted.jobId)
            barrier.release()
            val staleCode = staleAttempt.get(5, TimeUnit.SECONDS)
            check(staleCode == JobProblemCode.LEASE_LOST)
            check(repository.load(submitted.jobId)?.state?.terminal == true)
            val afterRelease = authorityBaseline(submitted.jobId)
            staleAttemptEvidence = JobConsoleStaleAttemptEvidence(
                pausedConnections = pausedConnections,
                pausedTransactions = 0,
                pausedLocks = 0,
                staleErrorCode = staleCode,
                lateEffectCount = afterRelease.effects - beforeRelease.effects,
                lateReceiptCount = afterRelease.receipts - beforeRelease.receipts,
            )
        }
        return submitted.jobId
    }

    private fun assertStaleAttemptEvidence() {
        val evidence = staleAttemptEvidence.requireNotNull("staleAttemptEvidence")
        check(evidence.pausedConnections == 0)
        check(evidence.pausedTransactions == 0)
        check(evidence.pausedLocks == 0)
        check(evidence.staleErrorCode == JobProblemCode.LEASE_LOST)
        check(evidence.lateEffectCount == 0L)
        check(evidence.lateReceiptCount == 0L)
    }

    private fun awaitReplacementClaim(
        tenantId: String,
    ): io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob =
        HighContentionAwait.value(
            timeout = Duration.ofSeconds(5),
            pollInterval = Duration.ofMillis(10),
            description = "replacement worker did not reclaim the expired lease",
        ) {
            repository.reclaimExpired(tenantId, Duration.ofSeconds(5))
        }

    private fun authorityBaseline(): JobConsoleAuthorityBaseline =
        dataSource.connection.use { connection ->
            JobConsoleAuthorityBaseline(
                jobs = connection.countRows("jobs"),
                effects = connection.countRows("job_history"),
                receipts = connection.countRows("job_outbox"),
            )
        }

    private fun authorityBaseline(jobId: UUID): JobConsoleAuthorityBaseline =
        dataSource.connection.use { connection ->
            JobConsoleAuthorityBaseline(
                jobs = connection.countRows("jobs", jobId),
                effects = connection.countRows("job_history", jobId),
                receipts = connection.countRows("job_outbox", jobId),
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

private fun java.sql.Connection.countRows(table: String, jobId: UUID): Long =
    prepareStatement("SELECT count(*) FROM $table WHERE job_id = ?").use { statement ->
        statement.setObject(1, jobId)
        statement.executeQuery().use { result ->
            check(result.next())
            result.getLong(1)
        }
    }
