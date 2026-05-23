package io.bluetape4k.workshop.resilience.decorators

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldStartWith
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.resilience4j.SuspendDecorators
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.resilience.AbstractResilienceTest
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.RetryRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SuspendDecorators] — programmatic Resilience4j API demonstration.
 *
 * This test shows the **bluetape4k-resilience4j** programmatic approach as an alternative
 * to AOP annotations (`@CircuitBreaker`, `@Retry`, etc.).
 *
 * ## Before (raw annotation approach):
 * ```kotlin
 * @CircuitBreaker(name = "backendA", fallbackMethod = "fallback")
 * @Retry(name = "backendA")
 * suspend fun callRemoteService(): String { ... }
 * ```
 *
 * ## After (bluetape4k programmatic approach):
 * ```kotlin
 * val result = SuspendDecorators.ofSupplier { callRemoteService() }
 *     .withCircuitBreaker(circuitBreaker)
 *     .withRetry(retry)
 *     .invoke()
 * ```
 *
 * ## Behavior / Contract
 * - `SuspendDecorators` chains are composable and testable without Spring AOP.
 * - Decorator order matters: outer decorators execute first.
 * - Recommended order: CircuitBreaker → Retry → TimeLimiter → Bulkhead.
 */
class SuspendDecoratorsTest : AbstractResilienceTest() {

    companion object : KLoggingChannel()

    @Autowired
    private val retryRegistry: RetryRegistry = uninitialized()

    @Test
    fun `successful suspend supplier decorated with circuit breaker and retry`() = runSuspendIO {
        val circuitBreaker = CircuitBreaker.ofDefaults("test-cb-success")
        val retry = io.github.resilience4j.retry.Retry.ofDefaults("test-retry-success")

        val result = SuspendDecorators.ofSupplier {
            "Hello from bluetape4k SuspendDecorators"
        }
            .withCircuitBreaker(circuitBreaker)
            .withRetry(retry)
            .invoke()

        result shouldBeEqualTo "Hello from bluetape4k SuspendDecorators"
        log.debug { "SuspendDecorators supplier result: $result" }
    }

    @Test
    fun `retry is triggered on transient failure — succeeds on 3rd attempt`() = runSuspendIO {
        val maxAttempts = 3
        val retry = io.github.resilience4j.retry.Retry.of(
            "test-retry-transient",
            io.github.resilience4j.retry.RetryConfig.custom<String>()
                .maxAttempts(maxAttempts)
                .retryExceptions(IOException::class.java)
                .build()
        )

        val callCount = AtomicInteger(0)

        val result = SuspendDecorators.ofSupplier {
            val attempt = callCount.incrementAndGet()
            if (attempt < maxAttempts) throw IOException("simulated failure on attempt $attempt")
            "success on attempt $attempt"
        }
            .withRetry(retry)
            .invoke()

        result shouldBeEqualTo "success on attempt $maxAttempts"
        callCount.get() shouldBeEqualTo maxAttempts
        log.debug { "Retry succeeded after $maxAttempts attempts" }
    }

    @Test
    fun `circuit breaker opens after repeated failures and rejects subsequent calls`() = runSuspendIO {
        val circuitBreaker = CircuitBreaker.of(
            "test-cb-open",
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(100f)   // 100% → opens immediately after 4 failures
                .waitDurationInOpenState(java.time.Duration.ofSeconds(10))
                .build()
        )

        // Trigger 4 failures to open the circuit breaker
        repeat(4) {
            runCatching {
                SuspendDecorators.ofSupplier<String> {
                    throw IOException("simulated failure")
                }
                    .withCircuitBreaker(circuitBreaker)
                    .invoke()
            }
        }

        circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.OPEN
        log.debug { "Circuit breaker opened after 4 failures" }

        // Next call must be rejected with CallNotPermittedException
        val rejectedResult = runCatching {
            SuspendDecorators.ofSupplier {
                "should not reach here"
            }
                .withCircuitBreaker(circuitBreaker)
                .invoke()
        }

        rejectedResult.isFailure.shouldBeTrue()
        rejectedResult.exceptionOrNull() shouldBeInstanceOf CallNotPermittedException::class
        log.debug { "Open circuit correctly rejected call: ${rejectedResult.exceptionOrNull()?.message}" }
    }

    @Test
    fun `circuit breaker with fallback — returns fallback value instead of throwing`() = runSuspendIO {
        val circuitBreaker = CircuitBreaker.of(
            "test-cb-fallback",
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(100f)
                .waitDurationInOpenState(java.time.Duration.ofSeconds(10))
                .build()
        )

        // Open the circuit breaker
        repeat(4) {
            runCatching {
                SuspendDecorators.ofSupplier<String> {
                    throw IOException("simulated failure")
                }
                    .withCircuitBreaker(circuitBreaker)
                    .invoke()
            }
        }

        circuitBreaker.state shouldBeEqualTo CircuitBreaker.State.OPEN

        // Call with fallback — no exception thrown
        val result = SuspendDecorators.ofSupplier<String> {
            "should not reach here"
        }
            .withCircuitBreaker(circuitBreaker)
            .withFallback { ex ->
                "fallback: ${ex?.message}"
            }
            .invoke()

        result shouldStartWith "fallback:"
        log.debug { "Fallback returned: $result" }
    }

    @Test
    fun `spring-registered circuit breaker used programmatically via SuspendDecorators`() = runSuspendIO {
        // Use backendA circuit breaker from the Spring-managed registry
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker(BACKEND_A)
        val retry = retryRegistry.retry(BACKEND_A)

        // Ensure circuit is closed before test
        circuitBreaker.transitionToClosedState()

        val callCount = AtomicInteger(0)

        val result = SuspendDecorators.ofSupplier {
            callCount.incrementAndGet()
            "Hello from Spring-registered backendA circuit breaker"
        }
            .withCircuitBreaker(circuitBreaker)
            .withRetry(retry)
            .invoke()

        result shouldBeEqualTo "Hello from Spring-registered backendA circuit breaker"
        callCount.get() shouldBeEqualTo 1
        log.debug { "Spring-registered CB result: $result, attempts: ${callCount.get()}" }
    }
}
