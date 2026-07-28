package io.bluetape4k.workshop.resilience.timelimiter

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldStartWith
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.resilience.AbstractResilienceTest
import io.bluetape4k.workshop.shared.web.httpGet
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.expectBody
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * TimeLimiter — slow response timeout 검증입니다.
 *
 * [io.github.resilience4j.timelimiter.TimeLimiter] 가 slow call 에 timeout 을 올바르게 강제하고,
 * 설정된 fallback method 가 `TimeoutException` 을 처리하는지 검증합니다.
 *
 * ## Configuration (application.yml)
 * ```yaml
 * resilience4j.timelimiter:
 *   configs:
 *     default:
 *       cancelRunningFuture: false
 *       timeoutDuration: 2s
 *   instances:
 *     backendA:
 *       baseConfig: default
 * ```
 *
 * ## 동작 / 계약
 * - `@TimeLimiter` 는 `Mono`, `Flux`, `CompletableFuture` return type 에만 적용됩니다.
 * - Coroutine `suspend` function 에는 `@TimeLimiter` 대신 `withTimeout {}` 을 사용합니다.
 * - timeout 이 발생하면 `TimeoutException` 이 throw 됩니다.
 * - `fallbackMethod` 를 지정하면 fallback 이 호출되고, 없으면 error 가 5xx 로 전파됩니다.
 */
class TimeLimiterTest : AbstractResilienceTest() {

    companion object : KLoggingChannel()

    @Autowired
    private val timeLimiterRegistry: TimeLimiterRegistry = uninitialized()

    @Test
    fun `backendA timeLimiter is configured with 2-second timeout`() {
        val timeLimiter = timeLimiterRegistry.timeLimiter(BACKEND_A)
        timeLimiter.shouldNotBeNull()

        val timeoutDuration = timeLimiter.timeLimiterConfig.timeoutDuration
        log.debug { "backendA TimeLimiter timeoutDuration=$timeoutDuration" }

        timeoutDuration shouldBeEqualTo Duration.ofSeconds(2)
    }

    @Test
    fun `backendB timeLimiter is configured with 2-second timeout`() {
        val timeLimiter = timeLimiterRegistry.timeLimiter(BACKEND_B)
        timeLimiter.shouldNotBeNull()

        val timeoutDuration = timeLimiter.timeLimiterConfig.timeoutDuration
        log.debug { "backendB TimeLimiter timeoutDuration=$timeoutDuration" }

        timeoutDuration shouldBeEqualTo Duration.ofSeconds(2)
    }

    @Test
    fun `monoTimeout endpoint returns fallback response when slow call exceeds 2s`() = runSuspendIO {
        // monoTimeout 은 3초 지연되고 TimeLimiter 는 2초에 동작하므로 monoFallback 이 실행됩니다.
        val response = webClient
            .httpGet("/$BACKEND_A/monoTimeout")
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult().responseBody
            .shouldNotBeNull()

        // Fallback 은 "Recovered Mono Exception: ..." 을 반환합니다.
        response shouldStartWith "Recovered"
        log.debug { "TimeLimiter fallback response: $response" }
    }

    @Test
    fun `fluxTimeout endpoint returns fallback response when slow flux exceeds 2s`() = runSuspendIO {
        // fluxTimeout 은 element 마다 3초 지연되고 TimeLimiter 는 2초에 동작하므로 fluxFallback 이 실행됩니다.
        val response = webClient
            .httpGet("/$BACKEND_A/fluxTimeout")
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult().responseBody
            .shouldNotBeNull()

        response shouldStartWith "Recovered"
        log.debug { "Flux TimeLimiter fallback response: $response" }
    }

    @Test
    fun `futureTimeout endpoint returns fallback response when blocking future exceeds 2s`() = runSuspendIO {
        // futureTimeout 은 5초 sleep 하고 TimeLimiter 는 2초에 동작하므로 futureFallback(TimeoutException)이 실행됩니다.
        val response = webClient
            .httpGet("/$BACKEND_A/futureTimeout")
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult().responseBody
            .shouldNotBeNull()

        response shouldStartWith "Recovered Future TimeoutException"
        log.debug { "Future TimeLimiter fallback response: $response" }
    }

    @Test
    fun `backendB monoTimeout programmatic chain returns fallback before slow Mono completes`() = runSuspendIO {
        val elapsed = measureTime {
            val response = webClient
                .httpGet("/$BACKEND_B/monoTimeout")
                .expectStatus().is2xxSuccessful
                .expectBody<String>()
                .returnResult().responseBody
                .shouldNotBeNull()

            response shouldStartWith "Recovered"
            log.debug { "BackendB Mono TimeLimiter fallback response: $response" }
        }

        elapsed shouldBeLessThan 3.seconds
    }

    @Test
    fun `backendB fluxTimeout programmatic chain returns fallback before slow Flux completes`() = runSuspendIO {
        val elapsed = measureTime {
            val response = webClient
                .httpGet("/$BACKEND_B/fluxTimeout")
                .expectStatus().is2xxSuccessful
                .expectBody<String>()
                .returnResult().responseBody
                .shouldNotBeNull()

            response shouldStartWith "Recovered"
            log.debug { "BackendB Flux TimeLimiter fallback response: $response" }
        }

        elapsed shouldBeLessThan 3.seconds
    }

    @Test
    fun `coroutine suspend — @TimeLimiter annotation is silently ignored for suspend functions`() = runSuspendIO {
        // @TimeLimiter 는 suspend function 에 적용되지 않으며 조용히 무시됩니다.
        // delay(3초)는 timeout 없이 완료되어 정상 result 를 반환합니다.
        //
        // KNOWN LIMITATION: Resilience4j 2.4.x + Spring Boot 4 + Kotlin 2.x 조합에서
        // @CircuitBreaker(fallbackMethod) 도 Kotlin suspend function 의 fallback 을 안정적으로 호출하지 못합니다.
        //
        // RECOMMENDED: suspend timeout + fallback 에는 bluetape4k SuspendDecorators programmatic API 를 사용합니다.
        // 동작 예제는 SuspendDecoratorsTest 를 참고합니다.
        val response = webClient
            .httpGet("/coroutines/$BACKEND_A/suspendTimeout")
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult().responseBody
            .shouldNotBeNull()

        // @TimeLimiter 는 무시되므로 delay(3초)가 완료되고 fallback 없이 정상 result 를 반환합니다.
        response shouldBeEqualTo "Hello World from backend A Coroutines"
        log.debug { "Suspend timeout completes normally (no @TimeLimiter enforcement): $response" }
    }
}
