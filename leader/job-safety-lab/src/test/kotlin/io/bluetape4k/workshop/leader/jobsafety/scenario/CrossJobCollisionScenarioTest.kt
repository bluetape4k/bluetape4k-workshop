package io.bluetape4k.workshop.leader.jobsafety.scenario

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import org.junit.jupiter.api.Test

internal class CrossJobCollisionScenarioTest {
    @Test
    fun `different jobs collide when job names guard one shared business resource`() {
        val scenarios = JobSafetyScenarioService()

        val unsafe = scenarios.run(JobSafetyScenario.CROSS_JOB_COLLISION, ScenarioMode.UNSAFE)
        val safe = scenarios.run(JobSafetyScenario.CROSS_JOB_COLLISION, ScenarioMode.SAFE)

        unsafe.finalSummary shouldNotBeEqualTo unsafe.expectedSummary
        safe.finalSummary shouldBeEqualTo safe.expectedSummary
        safe.executions.map { it.jobName } shouldBeEqualTo
            listOf(JobName("daily-summary"), JobName("backfill-summary"))
        safe.executions.map { it.conflictKey }.distinct().size shouldBeEqualTo 1
    }

    @Test
    fun `timeline is bounded and reports dropped educational events`() {
        val snapshot = JobSafetyScenarioService(timelineLimit = 3)
            .run(JobSafetyScenario.CROSS_JOB_COLLISION, ScenarioMode.SAFE)

        snapshot.timeline.size shouldBeEqualTo 3
        snapshot.droppedTimelineEvents shouldBeEqualTo 2
    }
}
