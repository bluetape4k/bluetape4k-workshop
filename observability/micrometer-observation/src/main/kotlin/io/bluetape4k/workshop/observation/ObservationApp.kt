package io.bluetape4k.workshop.observation

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ObservationApp {
    companion object: KLogging()
}

fun main(args: Array<String>) {
    runApplication<ObservationApp>(*args)
}
