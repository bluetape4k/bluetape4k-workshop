package io.bluetape4k.workshop.leader.jobsafety.web

import io.bluetape4k.workshop.leader.jobsafety.scenario.JobSafetyScenario
import io.bluetape4k.workshop.leader.jobsafety.scenario.JobSafetyScenarioService
import io.bluetape4k.workshop.leader.jobsafety.scenario.JobSafetyScenarioSnapshot
import io.bluetape4k.workshop.leader.jobsafety.scenario.ScenarioMode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/job-safety/unsafe")
@Profile("lab-unsafe & !prod")
@ConditionalOnProperty(
    prefix = "workshop.job-safety.lab",
    name = ["unsafe-enabled"],
    havingValue = "true",
)
class UnsafeJobSafetyController(
    private val scenarios: JobSafetyScenarioService,
) {
    @PostMapping("/scenarios/{scenario}/run")
    fun run(@PathVariable scenario: JobSafetyScenario): JobSafetyScenarioSnapshot =
        scenarios.run(scenario, ScenarioMode.UNSAFE)
}
