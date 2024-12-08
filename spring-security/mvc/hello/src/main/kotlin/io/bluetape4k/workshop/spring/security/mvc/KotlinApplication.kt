package io.bluetape4k.workshop.spring.security.mvc

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KotlinApplication {
    companion object: KLogging()
}

fun main(vararg args: String) {
    runApplication<KotlinApplication>(*args)
}
