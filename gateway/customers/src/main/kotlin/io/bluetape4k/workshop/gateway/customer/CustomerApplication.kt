package io.bluetape4k.workshop.gateway.customer

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CustomerApplication {

    companion object: KLogging() {
        init {
            log.info { "Starting Customer Application ..." }
        }
    }
}

fun main(vararg args: String) {
    runApplication<CustomerApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
