package io.bluetape4k.workshop.webflux.virtualthread

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WebFluxVirtualThreadApp {

    companion object: KLoggingChannel()

}

fun main(vararg args: String) {
    runApplication<WebFluxVirtualThreadApp>(*args)
}
