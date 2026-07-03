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
 * TimeLimiter — slow response timeout verification.
 *
 * Verifies that [io.github.resilience4j.timelimiter.TimeLimiter] correctly enforces timeout
 * on slow calls and that configured fallback methods handle the `TimeoutException`.
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
 * ## Behavior / Contract
 * - `@TimeLimiter` applies only to `Mono`, `Flux`, and `CompletableFuture` return types.
 * - Coroutine `suspend` functions: use `withTimeout {}` instead of `@TimeLimiter`.
 * - When the timeout fires, `TimeoutException` is thrown.
 * - If `fallbackMethod` is specified, the fallback is invoked; otherwise the error propagates as 5xx.
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
        // monoTimeout delays 3s; TimeLimiter fires at 2s → triggers monoFallback
        val response = webClient
            .httpGet("/$BACKEND_A/monoTimeout")
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult().responseBody
            .shouldNotBeNull()

        // Fallback returns "Recovered Mono Exception: ..."
        response shouldStartWith "Recovered"
        log.debug { "TimeLimiter fallback response: $response" }
    }

    @Test
    fun `fluxTimeout endpoint returns fallback response when slow flux exceeds 2s`() = runSuspendIO {
        // fluxTimeout delays 3s per element; TimeLimiter fires at 2s → triggers fluxFallback
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
        // futureTimeout sleeps 5s; TimeLimiter fires at 2s → triggers futureFallback(TimeoutException)
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
        // @TimeLimiter does NOT apply to suspend functions — it is silently ignored.
        // The delay(3s) completes without any timeout, returning the normal result.
        //
        // KNOWN LIMITATION: @CircuitBreaker(fallbackMethod) also does not reliably invoke the
        // fallback for Kotlin suspend functions in Resilience4j 2.4.x + Spring Boot 4 + Kotlin 2.x.
        //
        // RECOMMENDED: Use bluetape4k SuspendDecorators programmatic API for
        // suspend timeout + fallback (see SuspendDecoratorsTest for working examples).
        val response = webClient
            .httpGet("/coroutines/$BACKEND_A/suspendTimeout")
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult().responseBody
            .shouldNotBeNull()

        // @TimeLimiter is ignored → delay(3s) completes → normal result, no fallback
        response shouldBeEqualTo "Hello World from backend A Coroutines"
        log.debug { "Suspend timeout completes normally (no @TimeLimiter enforcement): $response" }
    }
}
