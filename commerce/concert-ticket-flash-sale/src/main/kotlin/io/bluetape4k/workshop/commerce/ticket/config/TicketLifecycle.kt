package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.workshop.commerce.ticket.web.TicketEventStream
import jakarta.annotation.PreDestroy
import java.util.concurrent.atomic.AtomicBoolean

/** observation과 worker resource가 닫히기 전에 admission을 중지합니다. */
class TicketLifecycle(private val eventStream: TicketEventStream) {
    private val acceptingForeground = AtomicBoolean(true)

    fun acceptsForegroundWork(): Boolean = acceptingForeground.get()

    @PreDestroy
    fun shutdown() {
        acceptingForeground.set(false)
        eventStream.stopNewConnections()
        eventStream.close()
    }
}
