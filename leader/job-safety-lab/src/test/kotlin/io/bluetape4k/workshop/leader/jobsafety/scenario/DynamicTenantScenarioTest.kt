package io.bluetape4k.workshop.leader.jobsafety.scenario

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.jobsafety.domain.JobExecutionState
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import org.junit.jupiter.api.Test

internal class DynamicTenantScenarioTest {
    @Test
    fun `removed tenant snapshot is rejected at commit`() {
        val scenarios = JobSafetyScenarioService()

        val safe = scenarios.run(JobSafetyScenario.DYNAMIC_TENANT, ScenarioMode.SAFE)
        val unsafe = scenarios.run(JobSafetyScenario.DYNAMIC_TENANT, ScenarioMode.UNSAFE)

        safe.executions.single().rejection shouldBeEqualTo JobRejectionReason.STALE_MEMBERSHIP
        safe.executions.single().state shouldBeEqualTo JobExecutionState.REJECTED
        unsafe.executions.single().state shouldBeEqualTo JobExecutionState.COMMITTED
    }
}
