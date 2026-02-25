package io.bluetape4k.workshop.bucket4j.hazelcast.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.atomic.AtomicInteger

@RestController
@RequestMapping("/coroutines")
class CoroutineController {

    companion object: KLoggingChannel()

    private val helloCounter = AtomicInteger(0)
    private val worldCounter = AtomicInteger(0)

    @GetMapping("/hello")
    suspend fun hello(): String {
        helloCounter.incrementAndGet()
        log.debug { "hello called. call count=${helloCounter.get()}" }
        return "Hello World"
    }

    @GetMapping("/world")
    suspend fun world(): String {
        worldCounter.incrementAndGet()
        log.debug { "world called. call count=${worldCounter.get()}" }
        return "Hello World"
    }
}
