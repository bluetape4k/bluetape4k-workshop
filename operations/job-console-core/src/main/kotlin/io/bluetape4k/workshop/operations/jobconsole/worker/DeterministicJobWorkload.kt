package io.bluetape4k.workshop.operations.jobconsole.worker

import io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob

class DeterministicJobWorkload {
    fun execute(job: ClaimedJob, unit: Long) {
        require(unit in 1..job.workUnits.toLong()) { "work unit is outside the declared job range" }
    }
}
