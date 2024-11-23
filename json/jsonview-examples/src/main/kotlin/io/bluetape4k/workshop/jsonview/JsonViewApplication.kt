package io.bluetape4k.workshop.jsonview

import io.bluetape4k.logging.KLogging
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class JsonViewApplication {

    companion object: KLogging()

}

fun main(vararg args: String) {
    runApplication<JsonViewApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
