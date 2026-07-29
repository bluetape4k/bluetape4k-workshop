package io.bluetape4k.workshop.leader.jobsafety.coordination

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRunRequest
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRunResult
import java.time.Duration

class JobRunCoordinator(
    private val leaderElection: LeaderElectionPort,
    private val fencingLease: FencingLeasePort,
    private val fencingTtl: Duration,
) {
    fun run(request: JobRunRequest, execute: (FencingLease) -> JobMutation): JobRunResult {
        val leader =
            leaderElection.tryAcquire(request.jobName)
                ?: return skipped(JobRejectionReason.LEADER_CONTENDED)

        return try {
            when (
                val acquired =
                    fencingLease.acquire(
                        conflictKey = request.conflictKey,
                        ownerId = request.fencingOwnerId,
                        ttl = fencingTtl,
                    )
            ) {
                is FenceAcquireResult.Acquired -> executeWithFence(acquired.lease, execute)
                is FenceAcquireResult.AlreadyOwned -> executeWithFence(acquired.lease, execute)
                FenceAcquireResult.Contended -> skipped(JobRejectionReason.FENCE_CONTENDED)
                is FenceAcquireResult.BackendFailure -> {
                    log.warn(acquired.cause) { "job_fence_acquire_failed reason=FENCE_BACKEND_FAILURE" }
                    failed(JobRejectionReason.FENCE_BACKEND_FAILURE)
                }
            }
        } finally {
            releaseSafely("leader") { leader.release() }
        }
    }

    private fun executeWithFence(
        lease: FencingLease,
        execute: (FencingLease) -> JobMutation,
    ): JobRunResult =
        try {
            try {
                when (val mutation = execute(lease)) {
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

    private companion object : KLogging()
}

sealed interface JobMutation {
    data object Committed : JobMutation

    data object EffectPending : JobMutation

    data class Rejected(val reason: JobRejectionReason) : JobMutation
}
