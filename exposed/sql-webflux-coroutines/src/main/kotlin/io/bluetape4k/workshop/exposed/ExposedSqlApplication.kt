package io.bluetape4k.workshop.exposed

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
// ExposedAutoConfiguration is a Spring Boot auto-configuration class that configures Exposed.
// @ImportAutoConfiguration(ExposedAutoConfiguration::class)
class ExposedApplication {

    companion object: KLoggingChannel()
}

fun main(args: Array<String>) {
    runApplication<ExposedApplication>(*args)
}
