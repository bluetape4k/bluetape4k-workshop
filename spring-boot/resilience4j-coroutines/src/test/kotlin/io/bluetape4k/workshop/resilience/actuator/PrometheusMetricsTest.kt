package io.bluetape4k.workshop.resilience.actuator

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.resilience.AbstractResilienceTest
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

/**
 * Resilience4j 2.x + Micrometer + Spring Boot 4 가 내보내는 Prometheus metric 이름을 검증합니다.
 *
 * ## Resilience4j 1.x -> 2.x 핵심 format 변경
 *
 * | Metric | Resilience4j 1.x (Counter) | Resilience4j 2.x (Timer) |
 * |--------|---------------------------|--------------------------|
 * | CB calls | `resilience4j_circuitbreaker_calls_total` | `resilience4j_circuitbreaker_calls_seconds_count` |
 *
 * Resilience4j 2.x 에서는 `resilience4j.circuitbreaker.calls` 가 **Timer** 로 등록됩니다.
 * 따라서 Prometheus scrape 결과는 `_seconds_count`, `_seconds_sum`, histogram bucket entry 를 생성합니다.
 * 이 metric 에서는 기존 `_total` suffix(Counter)가 더 이상 존재하지 않습니다.
 *
 * `resilience4j.circuitbreaker.not.permitted.calls` 는 여전히 **Counter** 이므로 `_total` suffix 를 가집니다.
 *
 * 해결: Issue #153
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
        // timer 가 채워지도록 몇 번 호출합니다.
        repeat(3) {
            webClient.get().uri("/$BACKEND_A/success").exchange()
        }

        val body = fetchPrometheusBody()
        val cbLines = body.lines()
            .filter { it.contains("resilience4j_circuitbreaker") }
        log.debug { "resilience4j_circuitbreaker metrics:\n${cbLines.joinToString("\n")}" }

        // Resilience4j 2.x: calls -> Timer -> _seconds_count suffix 입니다.
        body shouldContain "resilience4j_circuitbreaker_calls_seconds_count"
        body shouldContain "resilience4j_circuitbreaker_calls_seconds_sum"
    }

    @Test
    fun `CircuitBreaker state is exposed as a Gauge (no _total or _seconds suffix)`() {
        val body = fetchPrometheusBody()

        // Gauge 는 suffix 를 내보내지 않습니다.
        body shouldContain "resilience4j_circuitbreaker_state"
    }

    @Test
    fun `CircuitBreaker buffered calls are exposed as Gauge`() {
        val body = fetchPrometheusBody()
        body shouldContain "resilience4j_circuitbreaker_buffered_calls"
    }

    @Test
    fun `CircuitBreaker not-permitted calls retain Counter format (_total suffix)`() {
        // 후속 call 이 허용되지 않도록 circuit breaker 를 open 합니다.
        transitionToOpenState(BACKEND_A)
        repeat(3) {
            webClient.get().uri("/$BACKEND_A/success").exchange()
        }

        val body = fetchPrometheusBody()
        log.debug { "not_permitted metric lines:\n${body.lines().filter { it.contains("not_permitted") }.joinToString("\n")}" }

        // not.permitted.calls 는 여전히 Counter 이므로 _total suffix 를 가집니다.
        body shouldContain "resilience4j_circuitbreaker_not_permitted_calls_total"
    }
}
