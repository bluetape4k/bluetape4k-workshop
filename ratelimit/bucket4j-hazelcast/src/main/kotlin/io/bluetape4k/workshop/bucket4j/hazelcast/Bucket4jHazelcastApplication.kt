package io.bluetape4k.workshop.bucket4j.hazelcast

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WebfluxApplication {
    companion object: KLoggingChannel()
}

fun main(vararg args: String) {
    runApplication<WebfluxApplication>(*args)
}
