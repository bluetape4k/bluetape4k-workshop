package io.bluetape4k.workshop.webflux.virtualthread

import io.bluetape4k.logging.KLogging
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WebFluxVirtualThreadApp {

    companion object: KLogging()

}

fun main(vararg args: String) {
    runApplication<WebFluxVirtualThreadApp>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
