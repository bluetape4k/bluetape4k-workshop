package io.bluetape4k.workshop.resilience.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.resilience.service.Service
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.decorators.Decorators
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

@RestController
@RequestMapping("/backendB")
class BackendBController(
    @Qualifier("backendBService") private val serviceB: Service,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    threadPoolBulkheadRegistry: ThreadPoolBulkheadRegistry,
    bulkheadRegistry: BulkheadRegistry,
    retryRegistry: RetryRegistry,
    rateLimiterRegistry: RateLimiterRegistry,
    timeLimiterRegistry: TimeLimiterRegistry,
) {
    companion object: KLogging() {
        private const val BACKEND_B = "backendB"
    }

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker(BACKEND_B)
    private val bulkhead = bulkheadRegistry.bulkhead(BACKEND_B)
    private val threadPoolBulkhead = threadPoolBulkheadRegistry.bulkhead(BACKEND_B)
    private val retry = retryRegistry.retry(BACKEND_B)
    private val rateLimiter = rateLimiterRegistry.rateLimiter(BACKEND_B)
    private val timeLimiter = timeLimiterRegistry.timeLimiter(BACKEND_B)

    private val scheduledExecutorService = Executors.newScheduledThreadPool(3)

    @GetMapping("failure")
    fun failure(): String = execute { serviceB.failure() }

    @GetMapping("fallback")
    fun failureWithFallback() = execute { serviceB.failureWithFallback() }

    @GetMapping("success")
    fun success() = execute { serviceB.success() }

    @GetMapping("successException")
    fun successWithException() = execute { serviceB.successWithException() }

    @GetMapping("ignore")
    fun ignore(): String = Decorators.ofSupplier { serviceB.ignoreException() }
        .withCircuitBreaker(circuitBreaker)
        .withBulkhead(bulkhead)
        .get()


    @GetMapping("monoSuccess")
    fun monoSuccess() = executeMono { serviceB.monoSuccess() }

    @GetMapping("monoFailure")
    fun monoFailure() = executeMono { serviceB.monoFailure() }

    @GetMapping("monoTimeout")
    fun monoTimeout(): Mono<String> =
        executeMonoWithFallback(serviceB::monoTimeout, ::monoFallback)

    @GetMapping("fluxSuccess")
    fun fluxSuccess() = executeFlux { serviceB.fluxSuccess() }

    @GetMapping("fluxFailure")
    fun fluxFailure() = executeFlux { serviceB.fluxFailure() }

    @GetMapping("fluxTimeout")
    fun fluxTimeout(): Flux<String> {
        return executeFluxWithFallback(serviceB::fluxTimeout, ::fluxFallback)
    }

    @GetMapping("futureSuccess")
    fun futureSuccess() = executeFuture { serviceB.success() }

    @GetMapping("futureFailure")
    fun futureFailure() = executeFuture { serviceB.failure() }

    @GetMapping("futureTimeout")
    fun futureTimeout(): CompletableFuture<String> {
        return executeFutureWithFallback(::timeout, ::fallback)
    }

    private fun timeout(): String {
        Thread.sleep(3_000)    // Time Limiter 가 2s 로 설정되어 있으므로, timeout 이 발생합니다.
        return ""
    }

    private fun <T> execute(supplier: () -> T): T {
        return Decorators.ofSupplier(supplier)
            .withCircuitBreaker(circuitBreaker)
            .withBulkhead(bulkhead)
            .withRetry(retry)
            .get()
    }

    private fun <T> executeMono(publisher: () -> Mono<T>): Mono<T> {
        return publisher()
            .transform(BulkheadOperator.of(bulkhead))
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .transform(RetryOperator.of(retry))
    }


    private fun <T> executeMonoWithFallback(
        publisher: () -> Mono<T>,
        exceptionHandler: (Throwable) -> Mono<T>,
    ): Mono<T> {
        return publisher()
            .transform(BulkheadOperator.of(bulkhead))
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .transform(RetryOperator.of(retry))
            .onErrorResume(TimeoutException::class.java, exceptionHandler)
            .onErrorResume(CallNotPermittedException::class.java, exceptionHandler)
            .onErrorResume(BulkheadFullException::class.java, exceptionHandler)
    }

    private fun <T> executeFlux(publisher: () -> Flux<T>): Flux<T> {
        return publisher()
            .transform(BulkheadOperator.of(bulkhead))
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .transform(RetryOperator.of(retry))
    }

    private fun <T> executeFluxWithFallback(
        publisher: () -> Flux<T>,
        exceptionHandler: (Throwable) -> Flux<T>,
    ): Flux<T> {
        return publisher()
            .transform(BulkheadOperator.of(bulkhead))
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .transform(RetryOperator.of(retry))
            .onErrorResume(TimeoutException::class.java, exceptionHandler)
            .onErrorResume(CallNotPermittedException::class.java, exceptionHandler)
            .onErrorResume(BulkheadFullException::class.java, exceptionHandler)
    }

    private fun <T> executeFuture(supplier: () -> T): CompletableFuture<T> {
        return Decorators.ofSupplier(supplier)
            .withThreadPoolBulkhead(threadPoolBulkhead)
            .withTimeLimiter(timeLimiter, scheduledExecutorService)
            .withCircuitBreaker(circuitBreaker)
            .withRetry(retry, scheduledExecutorService)
            .get()
            .toCompletableFuture()
    }

    private fun <T> executeFutureWithFallback(
        supplier: () -> T,
        exceptionHandler: (Throwable) -> T,
    ): CompletableFuture<T> {

        val expectedExceptionTypes = listOf(
            TimeoutException::class.java,
            CallNotPermittedException::class.java,
            BulkheadFullException::class.java
        )

        return Decorators.ofSupplier(supplier)
            .withThreadPoolBulkhead(threadPoolBulkhead)
            .withTimeLimiter(timeLimiter, scheduledExecutorService)
            .withCircuitBreaker(circuitBreaker)
            .withFallback(expectedExceptionTypes, exceptionHandler)
            .get()
            .toCompletableFuture()
    }

    private fun fallback(ex: Throwable): String = "Recovered: $ex"
    private fun monoFallback(ex: Throwable): Mono<String> = Mono.just("Recovered: $ex")
    private fun fluxFallback(ex: Throwable): Flux<String> = Flux.just("Recovered: $ex")
}
