package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository

class JobWorkerEngine(
    private val repository: JobRepository,
    private val workload: DeterministicJobWorkload,
) {
    fun run(claimed: ClaimedJob) {
        var current = claimed
        for (unit in (claimed.completedChunk + 1)..claimed.workUnits.toLong()) {
            workload.execute(current, unit)
            val progress = ((unit * 100) / claimed.workUnits).toInt()
            val checkpoint = repository.checkpoint(current.lease, unit, progress)
            if (checkpoint.state == JobState.CANCELLED) return
            current = current.copy(lease = checkpoint.lease, completedChunk = checkpoint.completedChunk)
        }
        repository.complete(current.lease, JobSignal.SUCCESS)
    }
}
