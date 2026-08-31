package io.bluetape4k.workshop.leader.jobsafety.web

import io.bluetape4k.workshop.leader.jobsafety.audit.JobSafetyAuditReport
import io.bluetape4k.workshop.leader.jobsafety.audit.JobSafetyAuditReportPort
import io.bluetape4k.workshop.leader.jobsafety.effect.EffectOperations
import io.bluetape4k.workshop.leader.jobsafety.scenario.JobSafetyScenario
import io.bluetape4k.workshop.leader.jobsafety.scenario.JobSafetyScenarioService
import io.bluetape4k.workshop.leader.jobsafety.scenario.JobSafetyScenarioSnapshot
import io.bluetape4k.workshop.leader.jobsafety.scenario.ScenarioMode
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/job-safety")
class JobSafetyController(
    private val scenarios: JobSafetyScenarioService,
    private val effects: EffectOperations,
    private val audit: JobSafetyAuditReportPort,
) {
    @GetMapping("/scenarios")
    fun catalog(): List<JobSafetyScenario> = JobSafetyScenario.entries

    @PostMapping("/scenarios/{scenario}/run")
    fun run(@PathVariable scenario: JobSafetyScenario): JobSafetyScenarioSnapshot =
        scenarios.run(scenario, ScenarioMode.SAFE)

    @PostMapping("/scenarios/{scenario}/reset")
    fun reset(@PathVariable scenario: JobSafetyScenario): ScenarioResetResponse = ScenarioResetResponse(scenario)

    @PostMapping("/effects/deliver")
    fun deliver(): EffectOperationResponse = EffectOperationResponse(effects.deliverNext())

    @PostMapping("/effects/reconcile")
    fun reconcile(): EffectOperationResponse = EffectOperationResponse(effects.reconcileNext())

    @GetMapping("/audit")
    fun audit(): JobSafetyAuditReport = audit.report()
}
