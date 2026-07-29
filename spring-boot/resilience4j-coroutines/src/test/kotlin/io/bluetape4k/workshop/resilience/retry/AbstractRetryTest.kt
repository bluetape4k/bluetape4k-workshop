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
     * [RetryRegistry] 를 직접 사용해 retry metric counter 가 [count] 와 같은지 검증합니다.
     *
     * blocking caller(예: [RetryTest])는 registry 를 동기적으로 update 하므로
     * 해당 path 에서는 `currentCount + 1` delta assertion 이 신뢰 가능합니다.
     *
     * asynchronous publish semantic 을 가진 subclass(reactive `Mono`/`Flux`, `CompletableFuture`, coroutine)는
     * registry 를 동기적으로 update 하지 않으므로 [metricsAssertionEnabled] 를 override 해서 `false` 를 반환해야 합니다.
     *
     * **참고:** Prometheus endpoint assertion 은 생략합니다. Resilience4j + Spring Boot 4 조합은
     * 기대한 Prometheus pattern 과 다른 metric name format 을 생성합니다.
     * 추적: https://github.com/bluetape4k/bluetape4k-workshop/issues (area:resilience)
     */
    protected fun checkMetrics(kind: String, serviceName: String, count: Float) {
        val actual = getCurrentCount(kind, serviceName)
        log.debug { "checkMetrics kind=$kind service=$serviceName expected=$count actual=$actual asserting=${metricsAssertionEnabled()}" }
        if (metricsAssertionEnabled()) {
            actual shouldBeEqualTo count
        }
    }

    /**
     * [checkMetrics] 가 hard assertion 을 수행할지 제어합니다.
     *
     * metric update 가 asynchronous 한 subclass(reactive Mono/Flux, CompletableFuture, coroutine)에서는
     * `false` 를 반환하도록 override 합니다.
     */
    protected open fun metricsAssertionEnabled(): Boolean = true
}
