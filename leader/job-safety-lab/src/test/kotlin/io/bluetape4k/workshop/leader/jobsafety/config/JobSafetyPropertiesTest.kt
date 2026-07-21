package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.time.Duration

internal class JobSafetyPropertiesTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration::class.java)

    @Test
    fun `safe defaults bind successfully`() {
        runner.run { context ->
            val properties = context.getBean(JobSafetyProperties::class.java)

            properties.region shouldBeEqualTo "region-a"
            properties.timelineLimit shouldBeEqualTo 128
            properties.fencing.leaseTtl shouldBeEqualTo Duration.ofSeconds(5)
            properties.lab.unsafeEnabled shouldBeEqualTo false
        }
    }

    @Test
    fun `unsupported region fails closed`() {
        runner
            .withPropertyValues("workshop.job-safety.region=region-z")
            .run { context -> context.startupFailure.shouldNotBeNull() }
    }

    @Test
    fun `non-positive lease ttl fails closed`() {
        runner
            .withPropertyValues("workshop.job-safety.fencing.lease-ttl=0s")
            .run { context -> context.startupFailure.shouldNotBeNull() }
    }

    @Test
    fun `renew interval must be shorter than lease ttl`() {
        runner
            .withPropertyValues(
                "workshop.job-safety.fencing.lease-ttl=5s",
                "workshop.job-safety.fencing.renew-interval=5s",
            ).run { context -> context.startupFailure.shouldNotBeNull() }
    }

    @Test
    fun `timeline is bounded`() {
        runner
            .withPropertyValues("workshop.job-safety.timeline-limit=513")
            .run { context -> context.startupFailure.shouldNotBeNull() }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobSafetyProperties::class)
    private class PropertiesConfiguration
}
