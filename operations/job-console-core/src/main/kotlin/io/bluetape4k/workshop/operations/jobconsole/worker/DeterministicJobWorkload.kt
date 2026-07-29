package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode

class DeterministicWorkloadFailure(
    val retryable: Boolean,
) : RuntimeException(if (retryable) "retryable deterministic failure" else "non-retryable deterministic failure")

class DeterministicJobWorkload {
    fun execute(job: ClaimedJob, unit: Long) {
        require(unit in 1..job.workUnits.toLong()) { "work unit is outside the declared job range" }
        if (unit != 1L) return
        when (job.failureMode) {
            FailureMode.NONE -> Unit
            FailureMode.RETRY_ONCE -> if (job.lease.attempt == 1) throw DeterministicWorkloadFailure(retryable = true)
            FailureMode.ALWAYS_RETRYABLE -> throw DeterministicWorkloadFailure(retryable = true)
            FailureMode.NON_RETRYABLE -> throw DeterministicWorkloadFailure(retryable = false)
        }
    }
}
