package io.bluetape4k.workshop.bucket4j.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * Bucket4j Redis filter로 보호되는 reactive WebFlux endpoint입니다.
 *
 * 같은 starter 설정이 reactive controller와 coroutine controller를 모두 포괄한다는 점을 보여주려고
 * handler가 [Mono] 값을 직접 반환합니다.
 */
@RestController
@RequestMapping("/reactive")
class ReactiveController {

    companion object : KLoggingChannel() {
        private const val RESPONSE_BODY = "Hello World"
    }

    private val helloCounter = atomic(0)
    private val worldCounter = atomic(0)

    @GetMapping("/hello")
    fun hello(): Mono<String> {
        val helloCount = helloCounter.incrementAndGet()
        log.debug { "hello called. call count=$helloCount" }
        return Mono.just(RESPONSE_BODY)
    }

    @GetMapping("/world")
    fun world(): Mono<String> {
        val worldCount = worldCounter.incrementAndGet()
        log.debug { "world called. call count=$worldCount" }
        return Mono.just(RESPONSE_BODY)
    }
}
