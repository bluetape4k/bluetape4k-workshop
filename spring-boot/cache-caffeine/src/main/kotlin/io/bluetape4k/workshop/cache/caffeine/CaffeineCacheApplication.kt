package io.bluetape4k.workshop.cache.caffeine

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CaffeineCacheApplication {
    companion object: KLoggingChannel()
}

fun main(vararg args: String) {
    runApplication<CaffeineCacheApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
