package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

internal class JobSafetyLeaderObservationPropertiesTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration::class.java)

    @Test
    fun `safe defaults keep observation tags disabled`() {
        runner.run { context ->
            val properties = context.getBean(JobSafetyLeaderObservationProperties::class.java)

            properties.enabled shouldBeEqualTo true
            properties.includeLockName shouldBeEqualTo false
            properties.includeLeaderId shouldBeEqualTo false
            properties.includeExceptionDetails shouldBeEqualTo false
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
                val properties = context.getBean(JobSafetyLeaderObservationProperties::class.java)

                properties.enabled shouldBeEqualTo false
                properties.includeLockName shouldBeEqualTo true
                properties.includeLeaderId shouldBeEqualTo true
                properties.includeExceptionDetails shouldBeEqualTo true
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobSafetyLeaderObservationProperties::class)
    private class PropertiesConfiguration
}
