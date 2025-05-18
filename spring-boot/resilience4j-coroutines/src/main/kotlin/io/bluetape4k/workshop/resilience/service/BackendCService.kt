package io.bluetape4k.workshop.resilience.service

import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.resilience.exception.BusinessException
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.bulkhead.annotation.Bulkhead
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import io.github.resilience4j.timelimiter.annotation.TimeLimiter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

@Component("backendCService")
class BackendCService: Service {

    companion object: KLoggingChannel() {
        const val BACKEND_C = "backendC"
    }

    @CircuitBreaker(name = BACKEND_C)
    @Bulkhead(name = BACKEND_C)
    @Retry(name = BACKEND_C)
    override fun failure(): String {
        throw HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "원격 서비스 예외")
    }

    /**
     * 예외 발생 후 `fallback` 메소드를 호출합니다.
     */
    @CircuitBreaker(name = BACKEND_C, fallbackMethod = "fallback")
    override fun failureWithFallback(): String {
        return failure()
    }

    @CircuitBreaker(name = BACKEND_C)
    @Bulkhead(name = BACKEND_C)
    @Retry(name = BACKEND_C)
    override fun success(): String {
        return "Hello World from backend C"
    }

    @CircuitBreaker(name = BACKEND_C)
    @Bulkhead(name = BACKEND_C)
    override fun successWithException(): String {
        throw HttpClientErrorException(HttpStatus.BAD_REQUEST, "This is a remote client exception")
    }

    @CircuitBreaker(name = BACKEND_C)
    @Bulkhead(name = BACKEND_C)
    override fun ignoreException(): String {
        throw BusinessException("이 예외는 Backend C 의 CircuitBreaker에 의해 무시됩니다")
    }

    @TimeLimiter(name = BACKEND_C)
    @CircuitBreaker(name = BACKEND_C)
    @Retry(name = BACKEND_C)
    override fun fluxSuccess(): Flux<String> {
        return Flux.just("Hello", "World")
    }

    @CircuitBreaker(name = BACKEND_C)
    @Bulkhead(name = BACKEND_C)
    @Retry(name = BACKEND_C)
    override fun fluxFailure(): Flux<String> {
        return Flux.error(IOException("BAM!"))
    }

    @TimeLimiter(name = BACKEND_C)
    @CircuitBreaker(name = BACKEND_C, fallbackMethod = "fluxFallback")
    override fun fluxTimeout(): Flux<String> {
        return Flux.just("Hello World as Flux from backend C")
            .delayElements(Duration.ofSeconds(10))
    }


    @TimeLimiter(name = BACKEND_C)
    @CircuitBreaker(name = BACKEND_C)
    @Bulkhead(name = BACKEND_C)
    @Retry(name = BACKEND_C)
    override fun monoSuccess(): Mono<String> {
        return Mono.just("Hello World as Mono from backend C")
    }

    @CircuitBreaker(name = BACKEND_C)
    @Bulkhead(name = BACKEND_C)
    @Retry(name = BACKEND_C)
    override fun monoFailure(): Mono<String> {
        return Mono.error(IOException("BAM!"))
    }

    @TimeLimiter(name = BACKEND_C)
    @Bulkhead(name = BACKEND_C)
    @CircuitBreaker(name = BACKEND_C, fallbackMethod = "monoFallback")
    override fun monoTimeout(): Mono<String> {
        return Mono
            .just("Hello World as Mono from backend C")
            .delayElement(Duration.ofSeconds(10))
    }

    @Bulkhead(name = BACKEND_C, type = Bulkhead.Type.THREADPOOL)
    @TimeLimiter(name = BACKEND_C)
    @CircuitBreaker(name = BACKEND_C)
    @Retry(name = BACKEND_C)
    override fun futureSuccess(): CompletableFuture<String> {
        return completableFutureOf("Hello World as Future from backend C")
    }

    @Bulkhead(name = BACKEND_C, type = Bulkhead.Type.THREADPOOL)
    @TimeLimiter(name = BACKEND_C)
    @CircuitBreaker(name = BACKEND_C)
    @Retry(name = BACKEND_C)
    override fun futureFailure(): CompletableFuture<String> {
        return failedCompletableFutureOf(IOException("BAM!"))
    }

    @Bulkhead(name = BACKEND_C, type = Bulkhead.Type.THREADPOOL)
    @TimeLimiter(name = BACKEND_C)
    @CircuitBreaker(name = BACKEND_C, fallbackMethod = "futureFallback")
    override fun futureTimeout(): CompletableFuture<String> {
        return futureOf {
            Thread.sleep(5000)
            "Hello World as Future from backend A"
        }
    }

    private fun fallback(ex: HttpServerErrorException): String {
        return "Recovered HttpServerErrorException: ${ex.message}"
    }

    private fun fallback(ex: Exception): String {
        return "Recovered Exception: $ex"
    }

    private fun fluxFallback(ex: Exception): Flux<String> {
        return Flux.just("Recovered Flux Exception: $ex")
    }

    private fun monoFallback(ex: Exception): Mono<String> {
        return Mono.just("Recovered Mono Exception: $ex")
    }

    private fun futureFallback(ex: TimeoutException): CompletableFuture<String> {
        return completableFutureOf("Recovered Future TimeoutException: $ex")
    }

    private fun futureFallback(ex: BulkheadFullException): CompletableFuture<String> {
        return completableFutureOf("Recovered Future BulkheadFullException: $ex")
    }

    private fun futureFallback(ex: CallNotPermittedException): CompletableFuture<String> {
        return completableFutureOf("Recovered Future CallNotPermittedException: $ex")
    }
}
