package io.bluetape4k.workshop.application.event.custom

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class CustomEventPublisher(private val appEventPublisher: ApplicationEventPublisher) {

    companion object: KLoggingChannel()

    suspend fun publish(message: String) {
        val event = CustomEvent(this, message)
        log.debug { "Publish custom event. $event" }

        appEventPublisher.publishEvent(event)
    }
}
