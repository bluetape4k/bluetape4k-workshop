package io.bluetape4k.workshop.leader.jobsafety.web

import io.bluetape4k.workshop.leader.jobsafety.effect.EffectOperations
import io.bluetape4k.workshop.leader.jobsafety.effect.EffectWorkResult
import io.bluetape4k.workshop.leader.jobsafety.scenario.JobSafetyScenarioService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockServletContext
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

internal class JobSafetySecurityTest {
    private lateinit var context: AnnotationConfigWebApplicationContext
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        context = AnnotationConfigWebApplicationContext()
        context.servletContext = MockServletContext()
        context.register(SecurityTestConfiguration::class.java)
        context.refresh()
        mvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
    }

    @AfterEach
    fun tearDown() {
        context.close()
    }

    @Test
    fun `anonymous request is challenged`() {
        mvc.post("/api/job-safety/scenarios/LEASE_OVERRUN/run").andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `authenticated viewer can run a safe scenario`() {
        mvc.post("/api/job-safety/scenarios/LEASE_OVERRUN/run") {
            with(user("viewer").roles("JOB_SAFETY_VIEWER"))
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `viewer cannot reconcile effects`() {
        mvc.post("/api/job-safety/effects/reconcile") {
            with(user("viewer").roles("JOB_SAFETY_VIEWER"))
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `operator can reconcile and reset`() {
        val operator = user("operator").roles(JobSafetySecurityConfiguration.OPERATOR_ROLE)

        mvc.post("/api/job-safety/effects/reconcile") { with(operator) }.andExpect {
            status { isOk() }
        }
        mvc.post("/api/job-safety/scenarios/LEASE_OVERRUN/reset") { with(operator) }.andExpect {
            status { isOk() }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(JobSafetyController::class, JobSafetySecurityConfiguration::class)
    private class SecurityTestConfiguration {
        @Bean
        fun scenarioService(): JobSafetyScenarioService = JobSafetyScenarioService()

        @Bean
        fun effectOperations(): EffectOperations =
            object : EffectOperations {
                override fun deliverNext(): EffectWorkResult = EffectWorkResult.NO_WORK

                override fun reconcileNext(): EffectWorkResult = EffectWorkResult.NO_WORK
            }
    }
}
