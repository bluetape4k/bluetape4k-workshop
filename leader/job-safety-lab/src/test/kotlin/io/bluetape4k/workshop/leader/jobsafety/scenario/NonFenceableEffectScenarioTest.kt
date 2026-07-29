package io.bluetape4k.workshop.leader.jobsafety.scenario

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

internal class NonFenceableEffectScenarioTest {
    @Test
    fun `stable operation id and reconciliation prevent duplicate external application`() {
        val scenarios = JobSafetyScenarioService()

        val safe = scenarios.run(JobSafetyScenario.NON_FENCEABLE_EFFECT, ScenarioMode.SAFE)
        val unsafe = scenarios.run(JobSafetyScenario.NON_FENCEABLE_EFFECT, ScenarioMode.UNSAFE)

        safe.finalSummary shouldBeEqualTo 1L
        safe.timeline.map { it.code } shouldBeEqualTo
            listOf(
                "OUTBOX_COMMIT",
                "PROVIDER_APPLIED_TIMEOUT",
                "RECONCILIATION_REQUIRED",
                "QUERY_ORIGINAL_OPERATION",
                "RECEIPT_CONFIRMED",
            )
        unsafe.finalSummary shouldBeEqualTo 2L
    }
}
