package io.bluetape4k.workshop.leader.jobsafety.web

import io.bluetape4k.workshop.leader.jobsafety.effect.EffectOperations
import io.bluetape4k.workshop.leader.jobsafety.effect.EffectWorkResult
import io.bluetape4k.workshop.leader.jobsafety.scenario.JobSafetyScenarioService
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

internal class JobSafetyControllerTest {
    private val effects =
        object : EffectOperations {
            override fun deliverNext(): EffectWorkResult = EffectWorkResult.CONFIRMED

            override fun reconcileNext(): EffectWorkResult = EffectWorkResult.NO_WORK
        }
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(JobSafetyController(JobSafetyScenarioService(), effects, testAuditReportPort()))
            .build()

    @Test
    fun `catalog exposes the closed scenario set`() {
        mvc.get("/api/job-safety/scenarios").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(6) }
            jsonPath("$[0]") { value("CROSS_JOB_COLLISION") }
            jsonPath("$[5]") { value("NON_FENCEABLE_EFFECT") }
        }
    }

    @Test
    fun `safe run returns a bounded explanatory snapshot`() {
        mvc.post("/api/job-safety/scenarios/LEASE_OVERRUN/run").andExpect {
            status { isOk() }
            jsonPath("$.scenario") { value("LEASE_OVERRUN") }
            jsonPath("$.mode") { value("SAFE") }
            jsonPath("$.finalSummary") { value(42) }
            jsonPath("$.executions[1].rejection") { value("STALE_FENCE") }
        }
    }

    @Test
    fun `unknown scenario is rejected`() {
        mvc.post("/api/job-safety/scenarios/NOT_A_SCENARIO/run").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `effect operations expose explicit outcomes`() {
        mvc.post("/api/job-safety/effects/deliver").andExpect {
            status { isOk() }
            jsonPath("$.result") { value("CONFIRMED") }
        }
        mvc.post("/api/job-safety/effects/reconcile").andExpect {
            status { isOk() }
            jsonPath("$.result") { value("NO_WORK") }
        }
    }

    @Test
    fun `audit report exposes bounded observation without endpoint or credentials`() {
        mvc.get("/api/job-safety/audit").andExpect {
            status { isOk() }
            jsonPath("$.transport") { value("MEMORY") }
            jsonPath("$.enabled") { value(true) }
            jsonPath("$.recentEvents[0].status") { value("COMPLETED") }
            jsonPath("$.snapshot.accepted") { value(1) }
            jsonPath("$.meters[0]") { value("leader.audit.export.accepted") }
            jsonPath("$.endpoint") { doesNotExist() }
            jsonPath("$.authorization") { doesNotExist() }
        }
    }
}
