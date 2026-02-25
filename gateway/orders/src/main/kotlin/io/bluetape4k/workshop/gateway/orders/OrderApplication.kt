package io.bluetape4k.workshop.gateway.orders

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OrderApplication {

    companion object: KLogging() {
        init {
            log.info { "Starting Customer Application ..." }
        }
    }
}

fun main(vararg args: String) {
    runApplication<OrderApplication>(*args) 
}
