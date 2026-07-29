package io.bluetape4k.workshop.leader.jobsafety.execution

import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRunRequest
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRunResult
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobAssignmentSnapshot
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobRolloutSnapshot
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyJdbcExecutor
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyJdbcTransaction
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyRepositories
import java.io.Serializable
import java.time.Instant

data class FencedJobRequest(
    val run: JobRunRequest,
    val fencingToken: FencingToken,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

class FencedJobExecutionService(
    private val jdbc: JobSafetyJdbcExecutor,
    private val repositories: JobSafetyRepositories,
) {
    fun execute(request: FencedJobRequest): JobRunResult =
        jdbc.transaction {
            val run = request.run
            val assignment = repositories.assignment.findCurrent(this, run.tenantId)
            val currentRollout =
                repositories.rollout.findCurrent(this)
                    ?: return@transaction reject(this, request, JobRejectionReason.INCOMPATIBLE_VERSION)
            val preconditionFailure = authorityFailure(run, assignment, currentRollout)
            if (preconditionFailure != null) {
                return@transaction reject(this, request, preconditionFailure)
            }

            val now = Instant.now()
            val updated = repositories.resource.accept(this, run, request.fencingToken, now)
            if (updated != 1) {
                val currentResource = repositories.resource.findCurrent(this, run.conflictKey)
                val reason =
                    if (currentResource?.namespaceEpoch != run.namespaceEpoch) {
                        JobRejectionReason.STALE_NAMESPACE
                    } else {
                        JobRejectionReason.STALE_FENCE
                    }
                return@transaction reject(this, request, reason, now)
            }

            repositories.checkpoint.upsert(
                transaction = this,
                request = run,
                fencingToken = request.fencingToken,
                schemaVersion = currentRollout.checkpointSchemaVersion,
                now = now,
            )
            repositories.execution.record(
                transaction = this,
                request = run,
                fencingToken = request.fencingToken,
                state = JobExecutionState.COMMITTED,
                rejection = null,
                now = now,
            )
            repositories.outbox.enqueue(this, run, now)
            JobRunResult(JobExecutionState.COMMITTED, fencingToken = request.fencingToken)
        }

    private fun authorityFailure(
        request: JobRunRequest,
        assignment: JobAssignmentSnapshot?,
        rollout: JobRolloutSnapshot,
    ): JobRejectionReason? =
        when {
            assignment == null || !assignment.active || assignment.membershipRevision != request.membershipRevision ->
                JobRejectionReason.STALE_MEMBERSHIP

            assignment.regionId != request.regionId || assignment.regionEpoch != request.regionEpoch ->
                JobRejectionReason.WRONG_REGION

            request.contractVersion.value < rollout.minimumWriterVersion.value ||
                request.contractVersion.value < rollout.checkpointSchemaVersion ->
                JobRejectionReason.INCOMPATIBLE_VERSION

            rollout.namespaceEpoch != request.namespaceEpoch -> JobRejectionReason.STALE_NAMESPACE
            else -> null
        }

    private fun reject(
        transaction: JobSafetyJdbcTransaction,
        request: FencedJobRequest,
        reason: JobRejectionReason,
        now: Instant = Instant.now(),
    ): JobRunResult {
        repositories.execution.record(
            transaction = transaction,
            request = request.run,
            fencingToken = request.fencingToken,
            state = JobExecutionState.REJECTED,
            rejection = reason,
            now = now,
        )
        return JobRunResult(
            state = JobExecutionState.REJECTED,
            fencingToken = request.fencingToken,
            rejection = reason,
        )
    }
}
