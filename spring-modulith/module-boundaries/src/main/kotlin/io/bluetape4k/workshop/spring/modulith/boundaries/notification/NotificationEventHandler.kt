package io.bluetape4k.workshop.spring.modulith.boundaries.notification

import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.events.OrderPlacedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Notification module listener that consumes the ordering module's event contract.
 */
@Component
class NotificationEventHandler(
    private val notificationOutbox: NotificationOutbox,
) {

    @EventListener
    fun on(event: OrderPlacedEvent) {
        notificationOutbox.enqueue(event)
    }
}
