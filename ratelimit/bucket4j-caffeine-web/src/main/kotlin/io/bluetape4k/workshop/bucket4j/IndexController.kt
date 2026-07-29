package io.bluetape4k.workshop.bucket4j

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Bucket4j servlet filter로 보호되는 단순 WebMVC endpoint를 제공합니다.
 *
 * 이 예제가 `application-servlet.yml`의 URL별 quota 차이에 집중하도록
 * `/hello`와 `/world`는 의도적으로 같은 body를 반환합니다.
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
