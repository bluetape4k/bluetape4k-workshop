package io.bluetape4k.workshop.resilience

import io.bluetape4k.logging.KLogging
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ResilienceApplication {

    companion object: KLogging()
}

fun main(vararg args: String) {
    runApplication<ResilienceApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
