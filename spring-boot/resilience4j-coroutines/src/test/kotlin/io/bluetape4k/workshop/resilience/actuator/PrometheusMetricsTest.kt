package io.bluetape4k.workshop.resilience.actuator

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.resilience.AbstractResilienceTest
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

/**
 * Validates Prometheus metric names emitted by Resilience4j 2.x + Micrometer + Spring Boot 4.
 *
 * ## Key format change from Resilience4j 1.x → 2.x
 *
 * | Metric | Resilience4j 1.x (Counter) | Resilience4j 2.x (Timer) |
 * |--------|---------------------------|--------------------------|
 * | CB calls | `resilience4j_circuitbreaker_calls_total` | `resilience4j_circuitbreaker_calls_seconds_count` |
 *
 * In Resilience4j 2.x, `resilience4j.circuitbreaker.calls` is registered as a **Timer**,
 * so the Prometheus scrape produces `_seconds_count`, `_seconds_sum`, and histogram bucket entries.
 * The old `_total` suffix (Counter) no longer exists for this metric.
 *
 * `resilience4j.circuitbreaker.not.permitted.calls` remains a **Counter** → `_total` suffix.
 *
 * Resolves: Issue #153
 */
class PrometheusMetricsTest : AbstractResilienceTest() {

    companion object : KLoggingChannel()

    private fun fetchPrometheusBody(): String =
        webClient.get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult().responseBody
            .shouldNotBeNull()

    @Test
    fun `Prometheus endpoint returns 200`() {
        webClient.get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectStatus().is2xxSuccessful
    }

    @Test
    fun `CircuitBreaker calls metric uses Timer format (seconds_count) not Counter (_total)`() {
        // Trigger a few calls so that the timer is populated
        repeat(3) {
            webClient.get().uri("/$BACKEND_A/success").exchange()
        }

        val body = fetchPrometheusBody()
        val cbLines = body.lines()
            .filter { it.contains("resilience4j_circuitbreaker") }
        log.debug { "resilience4j_circuitbreaker metrics:\n${cbLines.joinToString("\n")}" }

        // Resilience4j 2.x: calls → Timer → _seconds_count suffix
        body shouldContain "resilience4j_circuitbreaker_calls_seconds_count"
        body shouldContain "resilience4j_circuitbreaker_calls_seconds_sum"
    }

    @Test
    fun `CircuitBreaker state is exposed as a Gauge (no _total or _seconds suffix)`() {
        val body = fetchPrometheusBody()

        // Gauge emits no suffix
        body shouldContain "resilience4j_circuitbreaker_state"
    }

    @Test
    fun `CircuitBreaker buffered calls are exposed as Gauge`() {
        val body = fetchPrometheusBody()
        body shouldContain "resilience4j_circuitbreaker_buffered_calls"
    }

    @Test
    fun `CircuitBreaker not-permitted calls retain Counter format (_total suffix)`() {
        // Open the circuit breaker so subsequent calls are not permitted
        transitionToOpenState(BACKEND_A)
        repeat(3) {
            webClient.get().uri("/$BACKEND_A/success").exchange()
        }

        val body = fetchPrometheusBody()
        log.debug { "not_permitted metric lines:\n${body.lines().filter { it.contains("not_permitted") }.joinToString("\n")}" }

        // not.permitted.calls is still a Counter → _total suffix
        body shouldContain "resilience4j_circuitbreaker_not_permitted_calls_total"
    }
}
