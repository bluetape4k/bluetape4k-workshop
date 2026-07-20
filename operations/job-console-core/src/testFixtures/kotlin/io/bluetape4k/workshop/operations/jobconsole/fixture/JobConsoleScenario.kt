package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope

data class JobConsoleScenario(
    val scope: DemoCallerScope = DemoCallerScope("tenant-a", "submitter-a"),
    val idempotencyKey: String = "scenario-key",
    val request: SubmitJobRequest = SubmitJobRequest(JobType.DOCUMENT_EXPORT, workUnits = 3),
)
