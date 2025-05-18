package io.bluetape4k.workshop.bucket4j.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/coroutines")
class CoroutineController {

    companion object: KLoggingChannel()

    private val helloCounter = atomic(0)
    private val worldCounter = atomic(0)

    @GetMapping("/hello")
    suspend fun hello(): String {
        helloCounter.incrementAndGet()
        log.debug { "hello called. call count=${helloCounter.value}" }
        return "Hello World"
    }

    @GetMapping("/world")
    suspend fun world(): String {
        worldCounter.incrementAndGet()
        log.debug { "world called. call count=${worldCounter.value}" }
        return "Hello World"
    }
}
