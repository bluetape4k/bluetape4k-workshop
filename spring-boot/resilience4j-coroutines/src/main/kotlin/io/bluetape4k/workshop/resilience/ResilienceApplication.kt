package io.bluetape4k.workshop.resilience

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ResilienceApplication {
    companion object: KLoggingChannel()
}

fun main(vararg args: String) {
    runApplication<ResilienceApplication>(*args)
}
