package io.bluetape4k.workshop.leader.jobsafety.web

import io.bluetape4k.workshop.leader.jobsafety.effect.EffectWorkResult
import io.bluetape4k.workshop.leader.jobsafety.scenario.JobSafetyScenario

data class EffectOperationResponse(val result: EffectWorkResult)

data class ScenarioResetResponse(
    val scenario: JobSafetyScenario,
    val reset: Boolean = true,
)
