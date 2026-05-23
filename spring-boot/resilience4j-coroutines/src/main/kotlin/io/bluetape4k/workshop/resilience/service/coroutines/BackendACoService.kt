package io.bluetape4k.workshop.resilience.service.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.resilience.exception.BusinessException
import io.github.resilience4j.bulkhead.annotation.Bulkhead
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Coroutines 에 Resilience4j 적용하기
 *
 * NOTE: TimeLimiter 는 suspend 함수를 지원하지 않습니다.
 */
@Service("backendACoService")
class BackendACoService: CoService {

    companion object: KLoggingChannel() {
        private const val BACKEND_A: String = "backendA"
    }


    @CircuitBreaker(name = BACKEND_A, fallbackMethod = "suspendFallback")
    override suspend fun suspendFailureWithFallback(): String {
        return suspendFailure()
    }

    @CircuitBreaker(name = BACKEND_A)
    @Bulkhead(name = BACKEND_A)
    override suspend fun suspendSuccessWithException(): String {
        throw HttpClientErrorException(HttpStatus.BAD_REQUEST, "This is a remote client exception")
    }

    @CircuitBreaker(name = BACKEND_A)
    @Bulkhead(name = BACKEND_A)
    override suspend fun suspendIgnoreException(): String {
        throw BusinessException("이 예외는 backend A 의 CircuitBreaker에 의해 무시됩니다")
    }

    @CircuitBreaker(name = BACKEND_A)
    @Bulkhead(name = BACKEND_A)
    @Retry(name = BACKEND_A)
    override suspend fun suspendSuccess(): String {
        return "Hello World from backend A Coroutines"
    }

    @CircuitBreaker(name = BACKEND_A)
    @Bulkhead(name = BACKEND_A)
    @Retry(name = BACKEND_A)
    override suspend fun suspendFailure(): String {
        throw IOException("BAM!")
    }

    @Bulkhead(name = BACKEND_A)
    @CircuitBreaker(name = BACKEND_A, fallbackMethod = "suspendFallback")
    override suspend fun suspendTimeout(): String {
        // @TimeLimiter must NOT be applied to Kotlin suspend functions.
        // Unlike Mono/Flux/CompletableFuture, @TimeLimiter causes unexpected TimeoutException
        // for suspend functions in Resilience4j 2.4.x — the annotation is NOT safely ignored.
        //
        // For timeout enforcement in coroutines, use kotlinx.coroutines.withTimeout:
        //   withTimeout(2_000L) { slowOperation() }
        //
        // The delay(3s) below completes without any Resilience4j timeout enforcement
        // because @TimeLimiter is deliberately absent.
        delay(3.seconds)
        return "Hello World from backend A Coroutines"
    }

    @CircuitBreaker(name = BACKEND_A)
    @Retry(name = BACKEND_A)
    override fun flowSuccess(): Flow<String> {
        return flowOf("Hello", "World")
    }

    /**
     * NOTE: Flow 반환하는 함수에는 Retry Annotation이 적용되지 않습니다.
     *
     * NOTE: `Flow.retry(retry)` 함수를 사용하세요
     *
     * @return
     */
    @CircuitBreaker(name = BACKEND_A)
    @Bulkhead(name = BACKEND_A)
    @Retry(name = BACKEND_A)
    override fun flowFailure(): Flow<String> {
        return flowOf("Hello", "World")
            .onStart { throw IOException("BAM!") }
    }

    @CircuitBreaker(name = BACKEND_A, fallbackMethod = "flowFallback")
    override fun flowTimeout(): Flow<String> {
        return flowOf("Hello", "World")
            .onStart { delay(3.seconds) }
    }

    private fun suspendFallback(ex: Throwable): String {
        return "Recovered: ${ex.message}"
    }

    private fun flowFallback(ex: Throwable): Flow<String> {
        return flowOf("Recovered: ${ex.message}")
    }
}
