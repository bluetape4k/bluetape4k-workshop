package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.codec.encodeBase58
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import java.security.MessageDigest

data class JobConsoleScenario(
    val scope: DemoCallerScope = DemoCallerScope("tenant-a", "submitter-a"),
    val idempotencyKey: String = "scenario-key",
    val request: SubmitJobRequest = SubmitJobRequest(JobType.DOCUMENT_EXPORT, workUnits = 3),
) {
    companion object {
        fun fromSeed(
            seed: String,
            ordinal: Int,
            workUnits: Int = 3,
        ): JobConsoleScenario {
            ordinal.requireZeroOrPositiveNumber("ordinal")
            val suffix = MessageDigest.getInstance("SHA-256")
                .digest("$seed:$ordinal".toByteArray(Charsets.UTF_8))
                .copyOf(12)
                .encodeBase58()
            return JobConsoleScenario(
                scope = DemoCallerScope("tenant-$suffix", "submitter-$suffix"),
                idempotencyKey = "request-$suffix",
                request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, workUnits),
            )
        }
    }
}
