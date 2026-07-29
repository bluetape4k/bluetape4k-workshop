package io.bluetape4k.workshop.leader.jobsafety.scenario

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.jobsafety.domain.ExecutionContractVersion
import io.bluetape4k.workshop.leader.jobsafety.domain.JobRejectionReason
import org.junit.jupiter.api.Test

internal class MixedVersionRolloutScenarioTest {
    @Test
    fun `minimum writer marker blocks the old worker after compatible rollout`() {
        val safe = JobSafetyScenarioService()
            .run(JobSafetyScenario.MIXED_VERSION_ROLLOUT, ScenarioMode.SAFE)

        safe.executions.single().rejection shouldBeEqualTo JobRejectionReason.INCOMPATIBLE_VERSION
        safe.timeline.map { it.code } shouldBeEqualTo
            listOf("EXPAND_COMPATIBLE_DEPLOY", "CHECKPOINT_SCHEMA_2", "MIN_WRITER_2", "OLD_WRITER_REJECTED")
    }

    @Test
    fun `rollout markers never downgrade in place`() {
        val protocol = RolloutProtocol()
        protocol.advanceCheckpointSchema(2)
        protocol.advanceMinimumWriter(ExecutionContractVersion(2))

        assertFailsWith<IllegalArgumentException> { protocol.advanceCheckpointSchema(1) }
        assertFailsWith<IllegalArgumentException> {
            protocol.advanceMinimumWriter(ExecutionContractVersion(1))
        }
    }
}
