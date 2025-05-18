package io.bluetape4k.workshop.resilience.service

import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.resilience.exception.BusinessException
import io.github.resilience4j.bulkhead.annotation.Bulkhead
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Service B 에서 Resilience4j 를 적용하지 않고,
 * BackendBService를 사용하는 BackendBController 에서 annotation 방식이 아닌 프로그래밍 방식으로 적용합니다.
 */
@Component("backendBService")
class BackendBService: Service {

    companion object: KLoggingChannel() {
        private const val BACKEND_B: String = "backendB"
    }

    override fun failure(): String {
        throw HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "This is a remote exception")
    }

    override fun failureWithFallback(): String {
        return runCatching { failure() }.getOrElse { ex -> fallback(ex) }
    }

    override fun success(): String {
        return "Hello World from backend B"
    }

    override fun successWithException(): String {
        throw HttpClientErrorException(HttpStatus.BAD_REQUEST, "This is a remote client exception")
    }

    override fun ignoreException(): String {
        throw BusinessException("이 예외는 backend B 의 CircuitBreaker에 의해 무시됩니다")
    }

    override fun fluxSuccess(): Flux<String> {
        return Flux.just("Hello", "World")
    }

    @Bulkhead(name = BACKEND_B)
    override fun fluxFailure(): Flux<String> {
        return Flux.error(IOException("BAM!"))
    }

    override fun fluxTimeout(): Flux<String> {
        return Flux.just("Hello World from backend B")
            .delayElements(Duration.ofSeconds(3)) // time limiter 가 2s 로 설정되어 있으므로, timeout 이 발생합니다.
    }

    override fun monoSuccess(): Mono<String> {
        return Mono.just("Hello World from backend B")
    }

    override fun monoFailure(): Mono<String> {
        return Mono.error(IOException("BAM!"))
    }

    override fun monoTimeout(): Mono<String> {
        return Mono.just("Hello World from backend B")
            .delayElement(Duration.ofSeconds(3)) // time limiter 가 2s 로 설정되어 있으므로, timeout 이 발생합니다.
    }

    override fun futureSuccess(): CompletableFuture<String> {
        return completableFutureOf("Hello World from backend B")
    }

    override fun futureFailure(): CompletableFuture<String> {
        return failedCompletableFutureOf(IOException("BAM!"))
    }

    override fun futureTimeout(): CompletableFuture<String> {
        return futureOf {
            Thread.sleep(5000)
            "Hello World from backend B"
        }
    }

    private fun fallback(ex: Throwable): String {
        log.debug { "Fallback 처리 ..." }
        return "Recovered : $ex"
    }

}
