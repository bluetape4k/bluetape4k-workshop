package io.bluetape4k.workshop.webflux

import io.bluetape4k.logging.KLogging
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WebsocketApplication {
    companion object: KLogging()
}

fun main(args: Array<String>) {
    runApplication<WebsocketApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
