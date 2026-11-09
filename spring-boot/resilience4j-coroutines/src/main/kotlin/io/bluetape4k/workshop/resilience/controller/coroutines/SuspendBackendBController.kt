package io.bluetape4k.workshop.resilience.controller.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.resilience4j.SuspendDecorators
import io.bluetape4k.workshop.resilience.service.coroutines.CoService
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.bulkhead.bulkhead
import io.github.resilience4j.kotlin.circuitbreaker.circuitBreaker
import io.github.resilience4j.kotlin.retry.retry
import io.github.resilience4j.kotlin.timelimiter.timeLimiter
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/coroutines/backendB")
class SuspendBackendBController(
    @param:Qualifier("backendBCoService") private val serviceB: CoService,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    threadPoolBulkheadRegistry: ThreadPoolBulkheadRegistry,
    bulkheadRegistry: BulkheadRegistry,
    retryRegistry: RetryRegistry,
    rateLimiterRegistry: RateLimiterRegistry,
    timeLimiterRegistry: TimeLimiterRegistry,
) {
    companion object: KLoggingChannel() {
        private const val BACKEND_B = "backendB"
    }

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker(BACKEND_B)
    private val bulkhead = bulkheadRegistry.bulkhead(BACKEND_B)
    private val threadPoolBulkhead = threadPoolBulkheadRegistry.bulkhead(BACKEND_B)
    private val retry = retryRegistry.retry(BACKEND_B)
    private val rateLimiter = rateLimiterRegistry.rateLimiter(BACKEND_B)
    private val timeLimiter = timeLimiterRegistry.timeLimiter(BACKEND_B)

    @GetMapping("suspendSuccess")
    suspend fun suspendSuccess() = execute { serviceB.suspendSuccess() }

    @GetMapping("suspendFailure")
    suspend fun suspendFailure(): String = execute { serviceB.suspendFailure() }

    @GetMapping("suspendFallback")
    suspend fun suspendFallback(): String = executeWithFallback(
        block = { serviceB.suspendFailure() },
        fallback = { e -> "Fallback: ${e?.message}" }
    )

    @GetMapping("suspendTimeout")
    suspend fun suspendTimeout(): String = executeWithFallback(serviceB::suspendTimeout, ::fallback)

    //
    // NOTE: Flow 반환 시 flowOn(Dispatchers.IO) 를 지정해줘야 Resilience4j 가 제대로 수행된다.
    // 

    @GetMapping("flowSuccess")
    fun flowSuccess(): Flow<String> =
        executeFlow { serviceB.flowSuccess() }.flowOn(Dispatchers.IO)

    @GetMapping("flowFailure")
    fun flowFailure(): Flow<String> =
        executeFlow { serviceB.flowFailure() }.flowOn(Dispatchers.IO)

    @GetMapping("flowTimeout")
    fun flowTimeout(): Flow<String> =
        executeFlowWithFallback(serviceB::flowTimeout, ::fallback).flowOn(Dispatchers.IO)

    private suspend inline fun <T> execute(crossinline block: suspend () -> T): T {
        return SuspendDecorators.ofSupplier { block() }
            .withCircuitBreaker(circuitBreaker)
            .withBulkhead(bulkhead)
            .withRetry(retry)
            .get()
    }

    private suspend fun <T> executeWithFallback(block: suspend () -> T, fallback: (Throwable?) -> T): T {
        return SuspendDecorators.ofSupplier(block)
            .withCircuitBreaker(circuitBreaker)
            .withBulkhead(bulkhead)
            .withRetry(retry)
            .withTimeLimiter(timeLimiter)
            .withFallback { e: Throwable? -> fallback(e) }   // fallback 기능 추가
            .get()
    }

    private inline fun <T> executeFlow(block: () -> Flow<T>): Flow<T> {
        return block()
            .circuitBreaker(circuitBreaker)
            .bulkhead(bulkhead)
            .retry(retry)
    }

    private fun <T> executeFlowWithFallback(block: () -> Flow<T>, fallback: (Throwable?) -> T): Flow<T> {
        return block()
            .circuitBreaker(circuitBreaker)
            .bulkhead(bulkhead)
            .retry(retry)
            .timeLimiter(timeLimiter)
            .catch { emit(fallback(it)) }
    }

    private fun fallback(ex: Throwable?): String = "Recovered: $ex"
}
