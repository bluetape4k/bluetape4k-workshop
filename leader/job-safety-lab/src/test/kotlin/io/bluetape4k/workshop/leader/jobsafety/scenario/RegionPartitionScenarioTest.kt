package io.bluetape4k.workshop.leader.jobsafety.scenario

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import org.junit.jupiter.api.Test

internal class RegionPartitionScenarioTest {
    @Test
    fun `partitioned non-home region cannot write even with a local fence`() {
        val scenarios = JobSafetyScenarioService()

        val safe = scenarios.run(JobSafetyScenario.REGION_PARTITION, ScenarioMode.SAFE)
        val unsafe = scenarios.run(JobSafetyScenario.REGION_PARTITION, ScenarioMode.UNSAFE)

        safe.executions.single().rejection shouldBeEqualTo JobRejectionReason.WRONG_REGION
        safe.resource.lastAcceptedFence shouldBeEqualTo null
        unsafe.executions.single().state shouldBeEqualTo JobExecutionState.COMMITTED
    }
}
