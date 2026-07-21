package io.bluetape4k.workshop.leader.jobsafety.scenario

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import org.junit.jupiter.api.Test

internal class LeaseOverrunScenarioTest {
    @Test
    fun `resumed stale worker cannot overwrite the takeover result`() {
        val snapshot = JobSafetyScenarioService()
            .run(JobSafetyScenario.LEASE_OVERRUN, ScenarioMode.SAFE)

        snapshot.executions.single { it.fencingToken == FencingToken(41L) }.rejection shouldBeEqualTo
            JobRejectionReason.STALE_FENCE
        snapshot.resource.lastAcceptedFence shouldBeEqualTo FencingToken(42L)
        snapshot.finalSummary shouldBeEqualTo snapshot.expectedSummary
    }

    @Test
    fun `unsafe resumed worker demonstrates the stale overwrite`() {
        val snapshot = JobSafetyScenarioService()
            .run(JobSafetyScenario.LEASE_OVERRUN, ScenarioMode.UNSAFE)

        snapshot.finalSummary shouldBeEqualTo 41L
        snapshot.expectedSummary shouldBeEqualTo 42L
        snapshot.timeline.map { it.code } shouldBeEqualTo
            listOf("A_ACQUIRE_41", "A_PAUSE", "A_EXPIRE", "B_ACQUIRE_42", "B_COMMIT", "A_RESUME")
    }
}
