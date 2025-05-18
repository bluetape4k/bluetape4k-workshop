package io.bluetape4k.workshop.spring.modulith.services

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SpringModulith {
    companion object: KLogging()
}

fun main(vararg args: String) {
    runApplication<SpringModulith>(*args)
}
