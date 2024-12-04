package io.bluetape4k.workshop.asynclogging.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.info
import io.bluetape4k.logging.trace
import io.bluetape4k.logging.warn
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class LoggingController {

    companion object: KLogging()

    @GetMapping("/")
    suspend fun index(): String {
        return execute {
            log.debug { "Call `/` endpoint at $it" }
        }
    }

    @GetMapping("/trace")
    suspend fun trace(): String {
        return execute {
            log.trace { "Call `/trace` endpoint at $it" }
        }
    }


    @GetMapping("/debug")
    suspend fun debug(): String {
        return execute {
            log.debug { "Call `/debug` endpoint at $it" }
        }
    }

    @GetMapping("/info")
    suspend fun info(): String {
        return execute {
            log.info { "Call `/info` endpoint at $it" }
        }
    }

    @GetMapping("/warn")
    suspend fun warn(): String {
        val ex = RuntimeException("WARN!")
        return execute {
            log.warn(ex) { "Call `/warn` endpoint at $it" }
        }
    }

    @GetMapping("/error")
    suspend fun error(): String {
        val ex = RuntimeException("Boom!")
        return execute {
            log.error(ex) { "Call `/error` endpoint at $it" }
        }
    }

    suspend fun execute(logging: (String) -> Unit): String {
        val nowStr = Instant.now().toString()
        logging(nowStr)
        return nowStr
    }
}
