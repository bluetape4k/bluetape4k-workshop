package io.bluetape4k.workshop.leader.jobsafety.coordination

import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.identity.LeaderIdSource
import io.bluetape4k.leader.metrics.LeaderAopMetricsContext
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRunRequest
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRunResult
import java.time.Duration
import java.util.concurrent.CancellationException
import kotlin.time.TimeSource

class JobRunCoordinator(
    private val leaderElection: LeaderElectionPort,
    private val fencingLease: FencingLeasePort,
    private val fencingTtl: Duration,
    private val observationRecorder: LeaderAopMetricsRecorder = LeaderAopMetricsRecorder.NoOp,
    private val observationListener: LeaderElectionListener = NoOpLeaderElectionListener,
    private val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
) {
    fun run(request: JobRunRequest, execute: (FencingLease) -> JobMutation): JobRunResult =
        runWithLease(request) { _, fencingLease -> execute(fencingLease) }

    /** user lease extension을 실행 callback에 명시적으로 노출하는 새 예제 경계입니다. */
    fun runWithLease(
        request: JobRunRequest,
        execute: (LeaderLease, FencingLease) -> JobMutation,
    ): JobRunResult = runInternal(request, execute)

    private fun runInternal(
        request: JobRunRequest,
        execute: (LeaderLease, FencingLease) -> JobMutation,
    ): JobRunResult {
        val lockName = "job-safety:${request.jobName.value}"
        val acquireStarted = TimeSource.Monotonic.markNow()
        observe { observationRecorder.onLockAttempt(lockName, leaderOptions) }
        val leader =
            leaderElection.tryAcquire(request.jobName)
                ?: return skippedAfterObservation(lockName)

        val context = LeaderAopMetricsContext.Identified(leader.ownerId.value, LeaderIdSource.AUTO)
        observe { observationListener.onElected(lockName) }
        observe { observationRecorder.onLockAcquired(lockName, leaderOptions, acquireStarted.elapsedNow(), context) }
        var taskStarted = false
        val taskStartedAt = TimeSource.Monotonic.markNow()

        return try {
            val bootstrap = fencingLease.bootstrap(request.conflictKey)
            val result = when (bootstrap) {
                FenceBootstrapResult.Ready -> when (
                    val acquired =
                        fencingLease.acquire(
                            conflictKey = request.conflictKey,
                            ownerId = request.fencingOwnerId,
                            ttl = fencingTtl,
                        )
                ) {
                is FenceAcquireResult.Acquired -> {
                    observe { observationRecorder.onTaskStarted(lockName, context) }
                    taskStarted = true
                    executeWithFence(leader, acquired.lease, execute)
                }
                is FenceAcquireResult.AlreadyOwned -> {
                    observe { observationRecorder.onTaskStarted(lockName, context) }
                    taskStarted = true
                    executeWithFence(leader, acquired.lease, execute)
                }
                FenceAcquireResult.Contended -> skipped(JobRejectionReason.FENCE_CONTENDED)
                is FenceAcquireResult.BackendFailure -> {
                    observe {
                        observationRecorder.onTaskFailed(
                            lockName,
                            taskStartedAt.elapsedNow(),
                            acquired.cause,
                            context,
                        )
                    }
                    log.warn(acquired.cause) { "job_fence_acquire_failed reason=FENCE_BACKEND_FAILURE" }
                    failed(JobRejectionReason.FENCE_BACKEND_FAILURE)
                }
                }
                is FenceBootstrapResult.BackendFailure -> {
                    observe {
                        observationRecorder.onTaskFailed(
                            lockName,
                            taskStartedAt.elapsedNow(),
                            bootstrap.cause,
                            context,
                        )
                    }
                    log.warn(bootstrap.cause) { "job_fence_bootstrap_failed reason=FENCE_BACKEND_FAILURE" }
                    failed(JobRejectionReason.FENCE_BACKEND_FAILURE)
                }
            }
            if (taskStarted) {
                if (result.state == JobExecutionState.FAILED) {
                    observe {
                        observationRecorder.onTaskFailed(
                            lockName,
                            taskStartedAt.elapsedNow(),
                            JobExecutionObservationFailure(result.rejection?.name),
                            context,
                        )
                    }
                } else {
                    observe { observationRecorder.onTaskFinished(lockName, taskStartedAt.elapsedNow(), context) }
                }
            }
            result
        } catch (e: CancellationException) {
            observe { observationRecorder.onTaskFailed(lockName, taskStartedAt.elapsedNow(), e, context) }
            throw e
        } catch (e: Exception) {
            observe { observationRecorder.onTaskFailed(lockName, taskStartedAt.elapsedNow(), e, context) }
            throw e
        } finally {
            releaseSafely("leader") { leader.release() }
            observe { observationListener.onRevoked(lockName) }
        }
    }

    private fun executeWithFence(
        leader: LeaderLease,
        lease: FencingLease,
        execute: (LeaderLease, FencingLease) -> JobMutation,
    ): JobRunResult =
        try {
            try {
                when (val mutation = execute(leader, lease)) {
                    JobMutation.Committed ->
                        JobRunResult(JobExecutionState.COMMITTED, fencingToken = lease.token)

                    JobMutation.EffectPending ->
                        JobRunResult(JobExecutionState.EFFECT_PENDING, fencingToken = lease.token)

                    is JobMutation.Rejected ->
                        JobRunResult(
                            state = JobExecutionState.REJECTED,
                            fencingToken = lease.token,
                            rejection = mutation.reason,
                        )
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "job_execution_failed reason=DOMAIN_FAILURE" }
                JobRunResult(
                    state = JobExecutionState.FAILED,
                    fencingToken = lease.token,
                    rejection = JobRejectionReason.DOMAIN_FAILURE,
                )
            }
        } finally {
            releaseSafely("fence") { lease.release() }
        }

    private fun releaseSafely(resource: String, release: () -> Unit) {
        try {
            release()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "job_coordination_release_interrupted resource=$resource" }
        } catch (e: Exception) {
            log.warn(e) { "job_coordination_release_failed resource=$resource" }
        }
    }

    private fun skipped(reason: JobRejectionReason): JobRunResult =
        JobRunResult(JobExecutionState.SKIPPED, rejection = reason)

    private fun failed(reason: JobRejectionReason): JobRunResult =
        JobRunResult(JobExecutionState.FAILED, rejection = reason)

    private fun skippedAfterObservation(lockName: String): JobRunResult {
        observe {
            observationRecorder.onLockNotAcquired(
                lockName,
                leaderOptions,
                SkipReason.CONTENTION,
                LeaderAopMetricsContext.Unknown,
            )
        }
        observe { observationListener.onSkipped(lockName) }
        return skipped(JobRejectionReason.LEADER_CONTENDED)
    }

    private inline fun observe(block: () -> Unit) {
        runCatching(block).onFailure { error ->
            log.warn(error) { "Leader observation callback failed" }
        }
    }

    private companion object : KLogging()
}

private object NoOpLeaderElectionListener : LeaderElectionListener

private class JobExecutionObservationFailure(rejection: String?) :
    IllegalStateException("job execution failed${rejection?.let { ": $it" } ?: ""}")

sealed interface JobMutation {
    data object Committed : JobMutation

    data object EffectPending : JobMutation

    data class Rejected(val reason: JobRejectionReason) : JobMutation
}
