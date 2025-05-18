package io.bluetape4k.workshop.spring.security.webflux.jwt

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class JwtApplication {
    companion object: KLoggingChannel()
}

fun main(vararg args: String) {
    runApplication<JwtApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
