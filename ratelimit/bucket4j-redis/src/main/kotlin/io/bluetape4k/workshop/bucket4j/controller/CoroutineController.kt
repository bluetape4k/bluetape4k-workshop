package io.bluetape4k.workshop.bucket4j.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Bucket4j Redis filter로 보호되는 coroutine WebFlux endpoint입니다.
 *
 * 예제가 starter 관리 Redis bucket store와 coroutine controller의 호환성에 집중하도록
 * handler를 의도적으로 작게 유지합니다.
 */
@RestController
@RequestMapping("/coroutines")
class CoroutineController {

    companion object : KLoggingChannel() {
        private const val RESPONSE_BODY = "Hello World"
    }

    private val helloCounter = atomic(0)
    private val worldCounter = atomic(0)

    @GetMapping("/hello")
    suspend fun hello(): String {
        val helloCount = helloCounter.incrementAndGet()
        log.debug { "hello called. call count=$helloCount" }
        return RESPONSE_BODY
    }

    @GetMapping("/world")
    suspend fun world(): String {
        val worldCount = worldCounter.incrementAndGet()
        log.debug { "world called. call count=$worldCount" }
        return RESPONSE_BODY
    }
}
