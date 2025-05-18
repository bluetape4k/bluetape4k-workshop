package io.bluetape4k.workshop.spring.modulith.events.b.transactions

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.events.util.LoggingConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(LoggingConfiguration::class)
class Application {

    companion object: KLogging()

}

fun main(vararg args: String) {
    runApplication<Application>(*args)
}
