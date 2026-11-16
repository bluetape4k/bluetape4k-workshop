package io.bluetape4k.workshop.leader.jobsafety.web

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.leader.jobsafety.scenario.JobSafetyScenarioService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

internal class UnsafeJobSafetyControllerConditionTest {

    companion object: KLogging()

    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(UnsafeControllerTestConfiguration::class.java)
            .withPropertyValues("workshop.job-safety.lab.unsafe-enabled=true")

    @Test
    fun `unsafe controller requires the isolated profile`() {
        runner.run { context ->
            context.getBeansOfType<UnsafeJobSafetyController>().shouldBeEmpty()
        }
    }

    @Test
    fun `unsafe controller requires the explicit feature flag`() {
        ApplicationContextRunner()
            .withUserConfiguration(UnsafeControllerTestConfiguration::class.java)
            .withInitializer { it.environment.setActiveProfiles("lab-unsafe") }
            .run { context ->
                context.getBeansOfType<UnsafeJobSafetyController>().shouldBeEmpty()
            }
    }

    @Test
    fun `unsafe controller is available in the isolated lab profile`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("lab-unsafe") }
            .run { context ->
                context.getBeansOfType<UnsafeJobSafetyController>() shouldHaveSize 1
            }
    }

    @Test
    fun `production always suppresses the unsafe controller`() {
        runner
            .withInitializer { it.environment.setActiveProfiles("prod", "lab-unsafe") }
            .run { context ->
                context.getBeansOfType<UnsafeJobSafetyController>().shouldBeEmpty()
            }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(UnsafeJobSafetyController::class)
    private class UnsafeControllerTestConfiguration {
        @Bean
        fun scenarioService(): JobSafetyScenarioService = JobSafetyScenarioService()
    }
}
