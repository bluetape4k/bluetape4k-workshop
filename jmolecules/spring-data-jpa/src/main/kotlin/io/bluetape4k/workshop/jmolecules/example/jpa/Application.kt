package io.bluetape4k.workshop.jmolecules.example.jpa

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application {
    companion object: KLogging()
}

fun main(vararg args: String) {
    runApplication<Application>(*args)
}
