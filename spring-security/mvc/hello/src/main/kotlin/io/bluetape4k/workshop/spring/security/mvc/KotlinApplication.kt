package io.bluetape4k.workshop.spring.security.mvc

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KotlinApplication {
    companion object: KLogging() {
        init {
            log.info { "Starting KotlinApplication" }
        }
    }
}

fun main(vararg args: String) {
    runApplication<KotlinApplication>(*args)
}
