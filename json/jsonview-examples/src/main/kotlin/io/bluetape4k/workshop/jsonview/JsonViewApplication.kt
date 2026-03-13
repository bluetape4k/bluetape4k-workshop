package io.bluetape4k.workshop.jsonview

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(proxyBeanMethods = false)
class JsonViewApplication {

    companion object: KLoggingChannel()

}

fun main(vararg args: String) {
    runApplication<JsonViewApplication>(*args)
}
