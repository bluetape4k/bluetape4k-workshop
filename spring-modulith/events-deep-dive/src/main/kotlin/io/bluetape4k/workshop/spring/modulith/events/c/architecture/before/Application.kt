package io.bluetape4k.workshop.spring.modulith.events.c.architecture.before

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.Application
import io.bluetape4k.workshop.spring.modulith.events.util.LoggingConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(LoggingConfiguration::class)
class Application {
    companion object: KLogging()
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
