package io.bluetape4k.workshop.leader.backendcomparison.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.micrometer.LeaderMetricTagOptions
import io.bluetape4k.leader.spring.observability.LeaderBackendDiagnosticsEndpoint
import io.bluetape4k.leader.spring.observability.LeaderBackendHealthIndicator
import io.bluetape4k.workshop.leader.backendcomparison.BackendComparisonLabApp
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.health.contributor.Status
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class LeaderBackendDiagnosticsContextTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration::class.java)
        .withPropertyValues(
            "spring.main.web-application-type=none",
            "management.endpoint.leaderBackendDiagnostics.enabled=true",
            "management.endpoints.web.exposure.include=health,info,leaderBackendDiagnostics",
            "bluetape4k.leader.observability.backend-health.enabled=false",
            "bluetape4k.leader.observability.backend-health.timeout=250ms",
            "bluetape4k.leader.observability.tracing.enabled=false",
            "bluetape4k.leader.observability.state-provider-bean=workshopLeaderElector",
        )

    private val webContextRunner = WebApplicationContextRunner()
        .withUserConfiguration(TestConfiguration::class.java)
        .withPropertyValues(
            "management.endpoint.leaderBackendDiagnostics.enabled=true",
            "management.endpoints.web.exposure.include=health,info,leaderBackendDiagnostics",
            "management.endpoint.health.show-details=always",
            "bluetape4k.leader.observability.backend-health.enabled=false",
            "bluetape4k.leader.observability.backend-health.timeout=250ms",
            "bluetape4k.leader.observability.tracing.enabled=false",
            "bluetape4k.leader.observability.state-provider-bean=workshopLeaderElector",
        )

    @Test
    fun `default context exposes passive selected profile diagnostics`() {
        contextRunner.run { context ->
            val endpoint = context.getBean(LeaderBackendDiagnosticsEndpoint::class.java)
            val diagnostics = endpoint.leaderBackendDiagnostics()

            context.getBean(BackendComparisonLabApp::class.java).shouldNotBeNull()
            context.getBean(LeaderElector::class.java)::class.simpleName shouldBeEqualTo
                "InstrumentedLeaderElector"
            diagnostics.descriptor.backendId shouldBeEqualTo "redis-lettuce"
            diagnostics.connectivity.status.name shouldBeEqualTo "NOT_CHECKED"
            diagnostics.connectivity.reason.name shouldBeEqualTo "NOT_CHECKED"
            context.containsBean("leaderBackendHealthIndicator").shouldBeFalse()
            context.containsBean("localLeaderElector").shouldBeFalse()
            context.containsBean("localSuspendLeaderElector").shouldBeTrue()

            context.getBean(MeterRegistry::class.java)
                .find("leader.backend.connectivity")
                .meters()
                .isEmpty()
                .shouldBeTrue()
        }
    }

    @Test
    fun `web application exposes diagnostics actuator route`() {
        webContextRunner.run { context ->
            val mockMvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            mockMvc.perform(get("/actuator/leaderBackendDiagnostics"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.descriptor.backendId").value("redis-lettuce"))
                .andExpect(jsonPath("$.connectivity.status").value("NOT_CHECKED"))
        }
    }

    @Test
    fun `diagnostics endpoint is opt out`() {
        contextRunner
            .withPropertyValues("management.endpoint.leaderBackendDiagnostics.enabled=false")
            .run { context ->
                context.getBeansOfType(LeaderBackendDiagnosticsEndpoint::class.java).isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `unknown configured backend id fails context startup`() {
        contextRunner
            .withPropertyValues("workshop.leader.backend-id=missing-backend")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
            }
    }

    @Test
    fun `unknown state provider bean fails context startup`() {
        contextRunner
            .withPropertyValues("bluetape4k.leader.observability.state-provider-bean=missing-provider")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
            }
    }

    @Test
    fun `active health maps status details and records low cardinality metric`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.leader.observability.backend-health.enabled=true",
                "workshop.leader.probe-outcome=UP",
            )
            .run { context ->
                val indicator = context.getBean(
                    "leaderBackendHealthIndicator",
                    LeaderBackendHealthIndicator::class.java,
                )
                val health = indicator.health()

                health.status shouldBeEqualTo Status.UP
                health.details["backend"] shouldBeEqualTo "redis-lettuce"
                health.details["connectivity"] shouldBeEqualTo "UP"
                health.details["reason"] shouldBeEqualTo "CONNECTED"
                health.details.keys.none { key ->
                    key.contains("credential", ignoreCase = true) ||
                        key.contains("exception", ignoreCase = true) ||
                        key.contains("endpoint", ignoreCase = true)
                }.shouldBeTrue()

                val registry = context.getBean(MeterRegistry::class.java)
                val meters = registry.find("leader.backend.connectivity").meters()
                meters.size shouldBeEqualTo 1
                meters.single().id.tags.map { it.key }.toSet() shouldBeEqualTo
                    setOf(LeaderMetricTagOptions.TAG_BACKEND_NAME, "status", "reason")

                val counter = registry
                    .find("leader.backend.connectivity")
                    .tag(LeaderMetricTagOptions.TAG_BACKEND_NAME, "redis-lettuce")
                    .tag("status", "UP")
                    .tag("reason", "CONNECTED")
                    .counter()
                    .shouldNotBeNull()
                counter.count() shouldBeEqualTo 1.0
            }
    }

    @Test
    fun `active health maps down unknown unsupported and exception outcomes`() {
        val expected = mapOf(
            ProbeOutcome.DOWN to (Status.DOWN to ("DOWN" to "DISCONNECTED")),
            ProbeOutcome.UNKNOWN to (Status.UNKNOWN to ("UNKNOWN" to "CLIENT_STATE_UNCONFIRMED")),
            ProbeOutcome.UNSUPPORTED to (Status.UNKNOWN to ("UNKNOWN" to "PROVIDER_UNSUPPORTED")),
            ProbeOutcome.EXCEPTION to (Status.UNKNOWN to ("UNKNOWN" to "PROVIDER_EXCEPTION")),
        )

        expected.forEach { (outcome, mapping) ->
            contextRunner
                .withPropertyValues(
                    "bluetape4k.leader.observability.backend-health.enabled=true",
                    "workshop.leader.probe-outcome=$outcome",
                )
                .run { context ->
                    val health = context.getBean(
                        "leaderBackendHealthIndicator",
                        LeaderBackendHealthIndicator::class.java,
                    ).health()

                    health.status shouldBeEqualTo mapping.first
                    health.details["connectivity"] shouldBeEqualTo mapping.second.first
                    health.details["reason"] shouldBeEqualTo mapping.second.second
                }
        }
    }

    @Test
    fun `cancelled active probe closes as unknown without leaking cancellation details`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.leader.observability.backend-health.enabled=true",
                "workshop.leader.probe-outcome=CANCELLED",
            )
            .run { context ->
                val health = context.getBean(
                    "leaderBackendHealthIndicator",
                    LeaderBackendHealthIndicator::class.java,
                ).health()

                health.status shouldBeEqualTo Status.UNKNOWN
                health.details.isEmpty().shouldBeTrue()

                context.getBean(MeterRegistry::class.java)
                    .find("leader.backend.connectivity")
                    .meters()
                    .isEmpty()
                    .shouldBeTrue()
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BackendComparisonLabApp::class)
    private class TestConfiguration {

        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }
}
