package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import java.time.Duration

class JobWorkerEngine(
    private val repository: JobRepository,
    private val workload: DeterministicJobWorkload,
) {
    fun runOnce(
        leaseDuration: Duration = Duration.ofSeconds(30),
        tenantLimit: Int = 100,
    ): Boolean {
        for (tenantId in repository.runnableTenantIds(tenantLimit)) {
            val claimed = repository.reclaimExpired(tenantId, leaseDuration)
                ?: repository.claimNext(tenantId, leaseDuration)
            if (claimed != null) {
                run(claimed)
                return true
            }
        }
        return false
    }

    fun run(claimed: ClaimedJob) {
        var current = claimed
        try {
            for (unit in (claimed.completedChunk + 1)..claimed.workUnits.toLong()) {
                workload.execute(current, unit)
                val progress = ((unit * 100) / claimed.workUnits).toInt()
                val checkpoint = repository.checkpoint(current.lease, unit, progress)
                if (checkpoint.state == JobState.CANCELLED) return
                current = current.copy(lease = checkpoint.lease, completedChunk = checkpoint.completedChunk)
            }
            repository.complete(current.lease, JobSignal.SUCCESS)
        } catch (failure: DeterministicWorkloadFailure) {
            repository.complete(
                current.lease,
                if (failure.retryable) JobSignal.RETRYABLE_FAILURE else JobSignal.NON_RETRYABLE_FAILURE,
            )
        }
    }
}
