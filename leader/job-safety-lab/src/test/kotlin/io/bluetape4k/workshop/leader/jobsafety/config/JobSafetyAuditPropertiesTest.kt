package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.time.Duration

internal class JobSafetyAuditPropertiesTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration::class.java)

    @Test
    fun `memory defaults are bounded and endpoint free`() {
        val properties = JobSafetyAuditProperties()

        properties.transport shouldBeEqualTo AuditTransport.MEMORY
        properties.queueCapacity shouldBeEqualTo 32
        properties.maxInFlight shouldBeEqualTo 4
        properties.maxPayloadBytes shouldBeEqualTo 64 * 1024
        properties.recentHistoryByteBudget shouldBeEqualTo 512 * 1024
        properties.maxBufferedBytes shouldBeEqualTo 72 * 1024 * 1024
        properties.endpoint shouldBeEqualTo null
    }

    @Test
    fun `memory configuration binds through spring`() {
        runner
            .withPropertyValues(
                "workshop.job-safety.audit.transport=MEMORY",
                "workshop.job-safety.audit.queue-capacity=16",
                "workshop.job-safety.audit.max-in-flight=4",
            ).run { context ->
                context.getBean(JobSafetyAuditProperties::class.java).apply {
                    transport shouldBeEqualTo AuditTransport.MEMORY
                    queueCapacity shouldBeEqualTo 16
                    maxInFlight shouldBeEqualTo 4
                }
            }
    }

    @Test
    fun `https authorization binds while remaining redacted`() {
        val secret = "Bearer bound-secret"

        runner
            .withPropertyValues(
                "workshop.job-safety.audit.transport=HTTPS",
                "workshop.job-safety.audit.endpoint=https://audit.example.test/audit",
                "workshop.job-safety.audit.allowed-hosts=audit.example.test",
                "workshop.job-safety.audit.headers.authorization=$secret",
            ).run { context ->
                val properties = context.getBean(JobSafetyAuditProperties::class.java)
                properties.headers.asMap()["Authorization"] shouldBeEqualTo secret
                properties.toString().contains(secret).shouldBeFalse()
            }
    }

    @Test
    fun `maximum combination exceeds budget before resources are built`() {
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditProperties(
                queueCapacity = 65_536,
                maxInFlight = 65_536,
                maxPayloadBytes = 1024 * 1024,
                maxBufferedBytes = 128L * 1024 * 1024,
            )
        }
    }

    @Test
    fun `checked reservation reports long overflow`() {
        assertFailsWith<IllegalArgumentException> {
            checkedAuditReservation(
                queueAndFlight = Long.MAX_VALUE,
                maxPayloadBytes = 1,
                recentHistoryByteBudget = 0,
                pendingMetadataBytes = 0,
            )
        }
    }

    @Test
    fun `https rejects private host and host outside exact allow list`() {
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditProperties(
                transport = AuditTransport.HTTPS,
                endpoint = "https://127.0.0.1/audit",
                allowedHosts = setOf("127.0.0.1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditProperties(
                transport = AuditTransport.HTTPS,
                endpoint = "https://audit.example.test/audit",
                allowedHosts = setOf("other.example.test"),
            )
        }
    }

    @Test
    fun `https endpoint canonicalizes host case and trailing dot`() {
        val properties =
            JobSafetyAuditProperties(
                transport = AuditTransport.HTTPS,
                endpoint = "https://AUDIT.Example.Test./audit",
                allowedHosts = setOf("audit.example.test"),
            )

        properties.endpointHost shouldBeEqualTo "audit.example.test"
        properties.canonicalAllowedHosts shouldBeEqualTo setOf("audit.example.test")
    }

    @Test
    fun `memory rejects endpoint and authorization`() {
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditProperties(endpoint = "https://audit.example.test/audit")
        }
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditProperties(headers = AuditHeaders(authorization = "Bearer token"))
        }
    }

    @Test
    fun `https requires endpoint and allow list`() {
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditProperties(transport = AuditTransport.HTTPS)
        }
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditProperties(
                transport = AuditTransport.HTTPS,
                endpoint = "https://audit.example.test/audit",
            )
        }
    }

    @Test
    fun `https rejects unsafe URI forms and address literals`() {
        val invalidEndpoints = listOf(
            "http://audit.example.test/audit",
            "https://user:password@audit.example.test/audit",
            "https://audit.example.test/audit?token=secret",
            "https://audit.example.test/audit#fragment",
            "https://localhost/audit",
            "https://127.0.0.1/audit",
            "https://2130706433/audit",
            "https://[::1]/audit",
        )

        invalidEndpoints.forEach { endpoint ->
            assertFailsWith<IllegalArgumentException> {
                JobSafetyAuditProperties(
                    transport = AuditTransport.HTTPS,
                    endpoint = endpoint,
                    allowedHosts = setOf(
                        "audit.example.test",
                        "localhost",
                        "127.0.0.1",
                        "2130706433",
                        "[::1]",
                    ),
                )
            }
        }
    }

    @Test
    fun `invalid bounds fail closed`() {
        assertFailsWith<IllegalArgumentException> { JobSafetyAuditProperties(queueCapacity = 0) }
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditProperties(queueCapacity = 4, maxInFlight = 5)
        }
        assertFailsWith<IllegalArgumentException> { JobSafetyAuditProperties(maxAttempts = 17) }
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditProperties(attemptTimeout = Duration.ofMinutes(5).plusNanos(1))
        }
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditProperties(initialBackoff = Duration.ofSeconds(2), maxBackoff = Duration.ofSeconds(1))
        }
        assertFailsWith<IllegalArgumentException> { JobSafetyAuditProperties(maxPayloadBytes = 0) }
        assertFailsWith<IllegalArgumentException> { JobSafetyAuditProperties(recentHistoryLimit = 0) }
        assertFailsWith<IllegalArgumentException> { JobSafetyAuditProperties(recentHistoryByteBudget = 0) }
        assertFailsWith<IllegalArgumentException> { JobSafetyAuditProperties(shutdownTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditProperties(shutdownTimeout = Duration.ofSeconds(30).plusNanos(1))
        }
    }

    @Test
    fun `authorization is bounded and absent from value rendering`() {
        val secret = "Bearer " + "x".repeat(32)
        val headers = AuditHeaders(authorization = secret)

        headers.toString().contains(secret).shouldBeFalse()
        headers shouldBeEqualTo AuditHeaders(authorization = "Bearer another-secret")
        headers.hashCode() shouldBeEqualTo AuditHeaders(authorization = "Bearer another-secret").hashCode()
        headers.asMap()["Authorization"] shouldBeEqualTo secret
        assertFailsWith<IllegalArgumentException> { AuditHeaders(authorization = "x".repeat(8193)) }
    }

    @Test
    fun `authorization header presence is distinct from absence`() {
        (AuditHeaders() == AuditHeaders(authorization = "Bearer secret")).shouldBeFalse()
        (AuditHeaders(authorization = "Bearer one") == AuditHeaders(authorization = "Bearer two")).shouldBeTrue()
    }

    @Test
    fun `properties rendering does not expose endpoint or authorization`() {
        val endpoint = "https://audit.example.test/audit"
        val secret = "Bearer confidential"
        val properties =
            JobSafetyAuditProperties(
                transport = AuditTransport.HTTPS,
                endpoint = endpoint,
                allowedHosts = setOf("audit.example.test"),
                headers = AuditHeaders(authorization = secret),
            )

        properties.toString().contains(endpoint).shouldBeFalse()
        properties.toString().contains(secret).shouldBeFalse()
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobSafetyAuditProperties::class)
    private class PropertiesConfiguration
}
