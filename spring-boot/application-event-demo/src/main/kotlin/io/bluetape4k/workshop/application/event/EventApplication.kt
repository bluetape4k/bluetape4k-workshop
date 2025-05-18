package io.bluetape4k.workshop.application.event

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.EnableAspectJAutoProxy

@SpringBootApplication
@EnableAspectJAutoProxy(proxyTargetClass = true)
class EventApplication {
    companion object: KLoggingChannel()
}

fun main(vararg args: String) {
    runApplication<EventApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
