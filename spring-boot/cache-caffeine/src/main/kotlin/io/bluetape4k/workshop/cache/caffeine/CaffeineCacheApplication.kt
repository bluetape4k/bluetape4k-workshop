package io.bluetape4k.workshop.cache.caffeine

import io.bluetape4k.logging.KLogging
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CaffeineCacheApplication {
    companion object: KLogging()
}

fun main(vararg args: String) {
    runApplication<CaffeineCacheApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
