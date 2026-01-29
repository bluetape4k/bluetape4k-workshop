package io.bluetape4k.workshop.bucket4j

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class IndexController {

    companion object: KLogging()

    private val helloCounter = atomic(0L)
    private val worldCounter = atomic(0L)

    @GetMapping("/hello")
    fun hello(): String {
        val helloCount = helloCounter.incrementAndGet()
        log.debug { "Hello called. $helloCount" }
        return "Hello World"
    }

    @GetMapping("/world")
    fun world(): String {
        val worldCount = worldCounter.incrementAndGet()
        log.debug { "World called. $worldCount" }
        return "Hello World"
    }
}
