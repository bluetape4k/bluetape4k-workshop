package io.bluetape4k.workshop.problem

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(proxyBeanMethods = false)
class ProblemApplication {
    companion object: KLogging()
}

fun main(vararg args: String) {
    runApplication<ProblemApplication>(*args)
}
