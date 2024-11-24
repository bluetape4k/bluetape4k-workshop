package io.bluetape4k.workshop.gatling

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KotlinGatlingApplication {

    companion object: KLogging() {
        init {
            log.info { "Start Spring Gatling Application ..." }
        }
    }
}

fun main(vararg args: String) {
    runApplication<KotlinGatlingApplication>(*args) {
        webApplicationType = WebApplicationType.SERVLET
    }
}
