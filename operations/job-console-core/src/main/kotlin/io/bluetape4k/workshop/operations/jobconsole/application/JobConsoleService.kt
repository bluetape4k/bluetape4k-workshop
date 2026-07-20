package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.signal.CancelSignal
import java.util.UUID

data class CancelServiceOutcome(
    val jobId: UUID,
    val state: JobState,
    val signalDegraded: Boolean,
)

class JobConsoleService(
    private val repository: JobRepository,
    private val cancelSignal: CancelSignal,
) {
    fun cancel(scope: DemoCallerScope, jobId: UUID): CancelServiceOutcome {
        val durable = repository.cancel(scope, jobId)
        val signalDegraded =
            durable.notificationRequired && runCatching { cancelSignal.publish(jobId) }.isFailure
        return CancelServiceOutcome(jobId, durable.state, signalDegraded)
    }
}
