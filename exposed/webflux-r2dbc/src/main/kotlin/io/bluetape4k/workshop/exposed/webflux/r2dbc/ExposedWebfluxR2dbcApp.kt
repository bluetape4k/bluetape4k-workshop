package io.bluetape4k.workshop.exposed.webflux.r2dbc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ExposedWebfluxR2dbcApp

fun main(vararg args: String) {
    runApplication<ExposedWebfluxR2dbcApp>(*args)
}
