package io.bluetape4k.workshop.spring.security.webflux

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.springframework.boot.WebApplicationType.REACTIVE
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KotlinWebfluxApplication {
    companion object: KLogging() {
        init {
            log.debug { "Starting KotlinWebfluxApplication" }
        }
    }
}

fun main(vararg args: String) {
    runApplication<KotlinWebfluxApplication>(*args) {
        webApplicationType = REACTIVE
    }
}
