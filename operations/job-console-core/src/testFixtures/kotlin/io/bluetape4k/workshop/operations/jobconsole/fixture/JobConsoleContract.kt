package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import java.time.Duration
import java.util.UUID

interface JobConsoleHttpDriver {
    suspend fun submit(request: SubmitJobRequest, key: String, scope: DemoCallerScope): JobSnapshot

    suspend fun snapshot(jobId: UUID, scope: DemoCallerScope): JobSnapshot

    suspend fun cancel(jobId: UUID, scope: DemoCallerScope): JobSnapshot

    suspend fun openEvents(jobId: UUID, scope: DemoCallerScope): JobEventProbe
}

interface JobEventProbe : AutoCloseable {
    suspend fun awaitEvent(timeout: Duration): JobEvent?
}

class JobConsoleContract(
    private val driver: JobConsoleHttpDriver,
) {
    suspend fun submitReplay(scenario: JobConsoleScenario): Pair<JobSnapshot, JobSnapshot> =
        driver.submit(scenario.request, scenario.idempotencyKey, scenario.scope) to
            driver.submit(scenario.request, scenario.idempotencyKey, scenario.scope)
}
