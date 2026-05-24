package io.bluetape4k.workshop.idgenerator

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(proxyBeanMethods = false)
class IdGeneratorApplication {
    companion object : KLogging()
}

fun main(args: Array<String>) {
    runApplication<IdGeneratorApplication>(*args)
}
