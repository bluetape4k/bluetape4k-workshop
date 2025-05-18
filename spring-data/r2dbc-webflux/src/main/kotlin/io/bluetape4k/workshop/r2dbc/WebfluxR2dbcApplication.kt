package io.bluetape4k.workshop.r2dbc

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WebfluxR2dbcApplication {
    companion object: KLoggingChannel()
}

fun main(vararg args: String) {
    runApplication<WebfluxR2dbcApplication>(*args)
}
