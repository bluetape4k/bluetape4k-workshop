package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob

class DeterministicWorkloadFailure(
    val retryable: Boolean,
): RuntimeException(if (retryable) "retryable deterministic failure" else "non-retryable deterministic failure")

class DeterministicJobWorkload {

    companion object: KLogging()

    fun execute(job: ClaimedJob, unit: Long) {
        unit.requireInRange(1, job.workUnits.toLong()) { "work unit is outside the declared job range" }

        if (unit != 1L) return

        when (job.failureMode) {
            FailureMode.NONE -> Unit
            FailureMode.RETRY_ONCE -> if (job.lease.attempt == 1) throw DeterministicWorkloadFailure(retryable = true)
            FailureMode.ALWAYS_RETRYABLE -> throw DeterministicWorkloadFailure(retryable = true)
            FailureMode.NON_RETRYABLE -> throw DeterministicWorkloadFailure(retryable = false)
        }
    }
}
