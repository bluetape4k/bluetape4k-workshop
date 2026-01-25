package io.bluetape4k.workshop.bucket4j.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

@RestController
@RequestMapping("/reactive")
class ReactiveController {

    companion object: KLoggingChannel()

    private val helloCounter = AtomicInteger(0)
    private val worldCounter = AtomicInteger(0)

    @GetMapping("/hello")
    fun hello(): Mono<String> {
        helloCounter.incrementAndGet()
        log.debug { "hello called. call count=${helloCounter.get()}" }
        return Mono.just("Hello World")
    }

    @GetMapping("/world")
    fun world(): Mono<String> {
        worldCounter.incrementAndGet()
        log.debug { "world called. call count=${worldCounter.get()}" }
        return Mono.just("Hello World")
    }
}
