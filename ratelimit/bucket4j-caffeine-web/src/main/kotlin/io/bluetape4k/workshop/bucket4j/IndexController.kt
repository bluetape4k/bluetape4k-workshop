package io.bluetape4k.workshop.bucket4j

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Provides simple WebMVC endpoints protected by Bucket4j servlet filters.
 *
 * `/hello` and `/world` intentionally return the same body so the example can
 * focus on per-URL quota differences configured in `application-servlet.yml`.
 */
@RestController
class IndexController {

    companion object : KLogging() {
        private const val RESPONSE_BODY = "Hello World"
    }

    private val helloCounter = atomic(0L)
    private val worldCounter = atomic(0L)

    @GetMapping("/hello")
    fun hello(): String {
        val helloCount = helloCounter.incrementAndGet()
        log.debug { "Hello called. $helloCount" }
        return RESPONSE_BODY
    }

    @GetMapping("/world")
    fun world(): String {
        val worldCount = worldCounter.incrementAndGet()
        log.debug { "World called. $worldCount" }
        return RESPONSE_BODY
    }
}
