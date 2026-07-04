package io.bluetape4k.workshop.application.event.custom.listeners

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.application.event.custom.CustomEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.mono
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class AnnotatedCoroutineCustomEventListener {

    companion object : KLoggingChannel()

    /**
     * Handles [CustomEvent] in a coroutine-friendly Reactor bridge.
     *
     * A suspend function cannot be used directly as an `@EventListener` method
     * because the compiler adds a continuation parameter. Wrapping the suspend
     * work in `mono { ... }` keeps the listener compatible with Spring events.
     */
    @EventListener(classes = [CustomEvent::class])
    fun handleEvent(event: CustomEvent) = mono(Dispatchers.IO) {
        doHandleEvent(event)
    }

    private suspend fun doHandleEvent(event: CustomEvent) {
        log.debug { "Handle custom event by @EventListener with coroutines. $event" }
        delay(100)
    }
}
