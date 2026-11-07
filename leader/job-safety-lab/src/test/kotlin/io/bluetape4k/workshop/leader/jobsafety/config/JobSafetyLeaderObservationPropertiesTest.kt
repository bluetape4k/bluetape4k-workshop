package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

internal class JobSafetyLeaderObservationPropertiesTest {

    companion object: KLogging()

    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration::class.java)

    @Test
    fun `safe defaults keep observation tags disabled`() {
        runner
            .run { context ->
                val properties = context.getBean<JobSafetyLeaderObservationProperties>()

                log.debug { "properties=$properties" }
                properties.enabled.shouldBeTrue()
                properties.includeLockName.shouldBeFalse()
                properties.includeLeaderId.shouldBeFalse()
                properties.includeExceptionDetails.shouldBeFalse()
            }
    }

    @Test
    fun `explicit values bind for controlled diagnostics`() {
        runner
            .withPropertyValues(
                "bluetape4k.leader.observation.enabled=false",
                "bluetape4k.leader.observation.include-lock-name=true",
                "bluetape4k.leader.observation.include-leader-id=true",
                "bluetape4k.leader.observation.include-exception-details=true",
            ).run { context ->
                val properties = context.getBean<JobSafetyLeaderObservationProperties>()

                log.debug { "properties=$properties" }
                properties.enabled.shouldBeFalse()
                properties.includeLockName.shouldBeTrue()
                properties.includeLeaderId.shouldBeTrue()
                properties.includeExceptionDetails.shouldBeTrue()
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobSafetyLeaderObservationProperties::class)
    private class PropertiesConfiguration
}
