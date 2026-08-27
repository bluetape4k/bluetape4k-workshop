package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionOutcome
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCanonicalizer
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCommand
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyCoordinator
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnerAction
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PreparedJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.ReplayableJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.persistence.JdbcJobSubmissionIdempotencyRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.workshop.operations.jobconsole.signal.CancelSignal
import io.bluetape4k.workshop.operations.jobconsole.signal.NoOpCancelSignal
import io.bluetape4k.workshop.operations.jobconsole.queue.EtaEstimator
import io.bluetape4k.workshop.operations.jobconsole.queue.QueueProjectionService
import io.bluetape4k.workshop.operations.jobconsole.queue.QueuePage
import io.bluetape4k.workshop.operations.jobconsole.observability.DependencyState
import io.bluetape4k.workshop.operations.jobconsole.observability.JobConsoleReadiness
import io.bluetape4k.jackson3.Jackson
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class CancelServiceOutcome(
    val jobId: UUID,
    val state: JobState,
    val signalDegraded: Boolean,
)

class JobConsoleService(
    private val repository: JobRepository,
    private val cancelSignal: CancelSignal = NoOpCancelSignal,
    private val clock: Clock = Clock.systemUTC(),
    private val etaEstimator: EtaEstimator = EtaEstimator(),
    private val boundedWaitEnabled: Boolean = true,
    private val expectedPolicyFingerprint: String? = null,
    private val executor: ExecutorService = ForkJoinPool.commonPool(),
) {
    private val submissionPolicy = JobSubmissionIdempotencyPolicy()
    private val submissionCanonicalizer = JobSubmissionCanonicalizer()
    private val submissionRepository =
        JdbcJobSubmissionIdempotencyRepository(repository.dataSource, repository, submissionPolicy, executor = executor)
    private val submissionCoordinator =
        JobSubmissionIdempotencyCoordinator(submissionRepository, submissionPolicy)
    private val jsonMapper = Jackson.defaultJsonMapper
    private val acceptingSubmissions = AtomicBoolean(true)
    private val activeSubmissions = AtomicInteger(0)

    fun submit(
        scope: DemoCallerScope,
        idempotencyKey: String,
        request: SubmitJobRequest,
    ): JobSubmissionOutcome {
        activeSubmissions.incrementAndGet()
        if (!acceptingSubmissions.get()) {
            activeSubmissions.decrementAndGet()
            throw JobRepositoryException(JobProblemCode.DEPENDENCY_UNAVAILABLE)
        }
        try {
            return if (boundedWaitEnabled) {
                submitBounded(scope, idempotencyKey, request)
            } else {
                submitLegacy(scope, idempotencyKey, request)
            }
        } finally {
            activeSubmissions.decrementAndGet()
        }
    }

    /** Stops new submissions before an adapter begins its bounded shutdown drain. */
    fun closeAdmission() {
        acceptingSubmissions.set(false)
    }

    fun isAcceptingSubmissions(): Boolean = acceptingSubmissions.get()

    fun awaitSubmissionQuiescence(timeout: Duration): Boolean {
        require(!timeout.isNegative) { "timeout must not be negative" }
        if (activeSubmissions.get() == 0) return true
        val deadline = System.nanoTime() + timeout.toNanos()
        while (activeSubmissions.get() > 0) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return false
            Thread.onSpinWait()
            if (remaining > TimeUnit.MILLISECONDS.toNanos(1)) {
                Thread.sleep(minOf(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L), 10L))
            }
        }
        return true
    }

    fun activeSubmissionCount(): Int = activeSubmissions.get()

    private fun submitBounded(
        scope: DemoCallerScope,
        idempotencyKey: String,
        request: SubmitJobRequest,
    ): JobSubmissionOutcome {
        val command =
            JobSubmissionCommand(
                scope = scope,
                keyHash = submissionCanonicalizer.keyHash(scope, idempotencyKey),
                requestFingerprint = submissionCanonicalizer.fingerprint(request),
                request = request,
                policyFingerprint = submissionPolicy.fingerprint,
            )
        return toPublicOutcome(
            submissionCoordinator.execute(
                command,
                object : JobSubmissionOwnerAction {
                    override fun prepare(ownership: io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnership): PreparedJobSubmission {
                        val initial =
                            JobSnapshot(
                                jobId = ownership.jobId,
                                jobType = request.jobType,
                                state = JobState.QUEUED,
                                progress = 0,
                                checkpoint = null,
                                queue = null,
                                version = 1,
                                updatedAt = clock.instant(),
                            )
                        return PreparedJobSubmission(
                            request = request,
                            responseBody = jsonMapper.writeValueAsString(initial).toByteArray(UTF_8),
                        )
                    }

                    override fun commit(
                        connection: java.sql.Connection,
                        ownership: io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnership,
                        prepared: PreparedJobSubmission,
                    ): ReplayableJobSubmission =
                        submissionRepository.finalizeOwner(connection, ownership, prepared, clock.instant())
                },
            ),
        )
    }

    private fun submitLegacy(
        scope: DemoCallerScope,
        idempotencyKey: String,
        request: SubmitJobRequest,
    ): JobSubmissionOutcome {
        val result = repository.submit(scope, idempotencyKey, request, clock.instant())
        val snapshot = snapshot(scope, result.jobId)
        val headers = mapOf("Idempotency-Replayed" to listOf(result.replayed.toString()))
        return if (result.replayed) {
            JobSubmissionOutcome.Replayed(snapshot, headers)
        } else {
            JobSubmissionOutcome.OwnerCompleted(snapshot, headers)
        }
    }

    private fun toPublicOutcome(
        outcome: io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome,
    ): JobSubmissionOutcome =
        when (outcome) {
            is io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.OwnerCompleted ->
                JobSubmissionOutcome.OwnerCompleted(outcome.snapshot.toJobSnapshot(), outcome.snapshot.responseHeaders)
            is io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.Replayed ->
                JobSubmissionOutcome.Replayed(outcome.snapshot.toJobSnapshot(), outcome.snapshot.responseHeaders)
            io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.Conflict -> JobSubmissionOutcome.Conflict
            io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.InFlightTimeout -> JobSubmissionOutcome.InFlightTimeout
            io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.WaiterOverflow -> JobSubmissionOutcome.WaiterOverflow
            io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOutcome.Abandoned -> JobSubmissionOutcome.Abandoned
        }

    private fun ReplayableJobSubmission.toJobSnapshot(): JobSnapshot =
        jsonMapper.readValue(responseBody.decodeToString(), JobSnapshot::class.java)

    fun snapshot(scope: DemoCallerScope, jobId: UUID): JobSnapshot {
        val stored = repository.load(scope, jobId)
        val queue =
            if (stored.state.terminal) {
                null
            } else {
                val now = clock.instant()
                val base = repository.queueProjection(scope.tenantId, jobId, now) ?: return snapshot(scope, jobId)
                val estimate =
                    etaEstimator.estimate(
                        repository.durationSamples(stored.jobType, now.minus(Duration.ofDays(30)), 100),
                        base.jobsAhead,
                        now,
                    )
                base.copy(
                    estimatedStartRange = estimate.startRange,
                    estimatedCompletionRange = estimate.completionRange,
                    confidence = estimate.confidence,
                    sampleSize = estimate.sampleSize,
                )
            }
        return JobSnapshot(
            jobId = stored.jobId,
            jobType = stored.jobType,
            state = stored.state,
            progress = stored.progress,
            checkpoint = stored.completedChunk.takeIf { it > 0 },
            queue = queue,
            version = stored.version,
            updatedAt = stored.updatedAt,
        )
    }

    fun myQueue(scope: DemoCallerScope, cursor: String?, pageSize: Int): QueuePage =
        QueueProjectionService.page(
            repository.submitterQueuePageRows(scope, QueueProjectionService.cursorSequence(cursor), pageSize),
            null,
            pageSize,
        )

    fun tenantQueue(tenantId: String, cursor: String?, pageSize: Int): QueuePage =
        QueueProjectionService.page(
            repository.queuePageRows(tenantId, QueueProjectionService.cursorSequence(cursor), pageSize),
            null,
            pageSize,
        )

    fun readiness(): JobConsoleReadiness {
        val postgresReady = repository.databaseReady()
        val redisAvailable = cancelSignal.isAvailable()
        val policyMatches = expectedPolicyFingerprint == null || expectedPolicyFingerprint == submissionPolicy.fingerprint
        val reason = when {
            !postgresReady -> "postgres"
            !policyMatches -> "policy"
            else -> null
        }
        return JobConsoleReadiness(
            ready = postgresReady && policyMatches,
            postgres = if (postgresReady) DependencyState.UP else DependencyState.DOWN,
            redis = if (redisAvailable) DependencyState.UP else DependencyState.DEGRADED,
            policyFingerprint = submissionPolicy.fingerprint,
            boundedWaitEnabled = boundedWaitEnabled,
            reason = reason,
        )
    }

    fun cancel(scope: DemoCallerScope, jobId: UUID): CancelServiceOutcome {
        val durable = repository.cancel(scope, jobId)
        val signalDegraded =
            durable.notificationRequired && runCatching { cancelSignal.publish(jobId) }.getOrNull()?.delivered != true
        return CancelServiceOutcome(jobId, durable.state, signalDegraded)
    }
}
