package io.bluetape4k.workshop.spring.modulith.boundaries.notification

import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.events.OrderPlacedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * ordering module 의 event contract 를 소비하는 notification module listener 입니다.
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
