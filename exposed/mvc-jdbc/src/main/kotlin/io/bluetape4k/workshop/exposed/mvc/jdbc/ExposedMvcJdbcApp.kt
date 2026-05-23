package io.bluetape4k.workshop.exposed.mvc.jdbc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ExposedMvcJdbcApp

fun main(args: Array<String>) {
    runApplication<ExposedMvcJdbcApp>(*args)
}
