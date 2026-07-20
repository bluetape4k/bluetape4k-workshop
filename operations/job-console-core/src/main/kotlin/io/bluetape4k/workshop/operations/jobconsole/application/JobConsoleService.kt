package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.signal.CancelSignal
import io.bluetape4k.workshop.operations.jobconsole.signal.NoOpCancelSignal
import io.bluetape4k.workshop.operations.jobconsole.queue.EtaEstimator
import io.bluetape4k.workshop.operations.jobconsole.queue.QueueProjectionService
import io.bluetape4k.workshop.operations.jobconsole.queue.QueuePage
import io.bluetape4k.workshop.operations.jobconsole.observability.DependencyState
import io.bluetape4k.workshop.operations.jobconsole.observability.JobConsoleReadiness
import java.time.Clock
import java.time.Duration
import java.util.UUID

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
) {
    fun submit(
        scope: DemoCallerScope,
        idempotencyKey: String,
        request: SubmitJobRequest,
    ): JobSnapshot {
        val submitted = repository.submit(scope, idempotencyKey, request, clock.instant())
        return snapshot(scope, submitted.jobId)
    }

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
        QueueProjectionService.page(repository.submitterQueueRows(scope), cursor, pageSize)

    fun tenantQueue(tenantId: String, cursor: String?, pageSize: Int): QueuePage =
        QueueProjectionService.page(repository.queueRows(tenantId, null, 100), cursor, pageSize)

    fun readiness(): JobConsoleReadiness {
        val postgresReady = repository.databaseReady()
        val redisAvailable = cancelSignal.isAvailable()
        return JobConsoleReadiness(
            ready = postgresReady,
            postgres = if (postgresReady) DependencyState.UP else DependencyState.DOWN,
            redis = if (redisAvailable) DependencyState.UP else DependencyState.DEGRADED,
        )
    }

    fun cancel(scope: DemoCallerScope, jobId: UUID): CancelServiceOutcome {
        val durable = repository.cancel(scope, jobId)
        val signalDegraded =
            durable.notificationRequired && runCatching { cancelSignal.publish(jobId) }.getOrNull()?.delivered != true
        return CancelServiceOutcome(jobId, durable.state, signalDegraded)
    }
}
