package io.bluetape4k.workshop.spring.modulith.boundaries.notification

import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.events.OrderPlacedEvent
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory notification-owned outbox used to prove event-driven handoff.
 */
@Component
class NotificationOutbox {

    private val messages = ConcurrentHashMap<String, NotificationMessage>()

    fun enqueue(event: OrderPlacedEvent): NotificationMessage {
        val message = NotificationMessage(
            orderId = event.orderId,
            customerId = event.customerId,
            message = "Order ${event.orderId} was accepted for SKU ${event.sku}.",
        )
        messages[event.orderId] = message
        return message
    }

    fun find(orderId: String): NotificationMessage? =
        messages[orderId]

    fun all(): List<NotificationMessage> =
        messages.values.sortedBy { it.orderId }

    fun reset() {
        messages.clear()
    }
}
