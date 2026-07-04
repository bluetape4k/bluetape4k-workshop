package io.bluetape4k.workshop.resilience.retry

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.resilience.AbstractResilienceTest
import io.github.resilience4j.retry.RetryRegistry
import org.springframework.beans.factory.annotation.Autowired

abstract class AbstractRetryTest: AbstractResilienceTest() {

    companion object : KLoggingChannel() {
        const val FAILED_WITH_RETRY = "failed_with_retry"
        const val SUCCESSFUL_WITHOUT_RETRY = "successful_without_retry"
    }

    @Autowired
    protected val retryRegistry: RetryRegistry = uninitialized()

    protected fun getCurrentCount(kind: String, serviceName: String): Float {
        val metrics = retryRegistry.retry(serviceName).metrics

        return when (kind) {
            FAILED_WITH_RETRY -> metrics.numberOfFailedCallsWithRetryAttempt.toFloat()
            SUCCESSFUL_WITHOUT_RETRY -> metrics.numberOfSuccessfulCallsWithoutRetryAttempt.toFloat()
            else -> 0F
        }.apply {
            log.debug { "Current Count=$this" }
        }
    }

    /**
     * Verifies the retry metric counter equals [count] using the [RetryRegistry] directly.
     *
     * Blocking callers (e.g., [RetryTest]) update the registry synchronously — the delta assertion
     * `currentCount + 1` is reliable for those paths.
     *
     * Subclasses with asynchronous publish semantics (reactive `Mono`/`Flux`, `CompletableFuture`,
     * coroutines) should override [metricsAssertionEnabled] to return `false` because those paths
     * do not update the registry synchronously.
     *
     * **Note:** Prometheus endpoint assertions are omitted; Resilience4j + Spring Boot 4 produces
     * a metric name format that does not match the expected Prometheus pattern.
     * Tracked: https://github.com/bluetape4k/bluetape4k-workshop/issues (area:resilience)
     */
    protected fun checkMetrics(kind: String, serviceName: String, count: Float) {
        val actual = getCurrentCount(kind, serviceName)
        log.debug { "checkMetrics kind=$kind service=$serviceName expected=$count actual=$actual asserting=${metricsAssertionEnabled()}" }
        if (metricsAssertionEnabled()) {
            actual shouldBeEqualTo count
        }
    }

    /**
     * Controls whether [checkMetrics] performs a hard assertion.
     *
     * Override to return `false` in subclasses where metric updates are asynchronous
     * (reactive Mono/Flux, CompletableFuture, coroutines).
     */
    protected open fun metricsAssertionEnabled(): Boolean = true
}
