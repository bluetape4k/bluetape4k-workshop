package io.bluetape4k.workshop.bucket4j.advanced

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(proxyBeanMethods = false)
class Bucket4jAdvancedApplication {
    companion object : KLoggingChannel()
}

fun main(vararg args: String) {
    runApplication<Bucket4jAdvancedApplication>(*args)
}
