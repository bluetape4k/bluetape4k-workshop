package io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.springdata

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.springdata.Order.OrderCompleted
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

class OrderListeners {

    @Component
    class AnnotatedListener {

        companion object: KLogging()

        @EventListener
        fun on(event: OrderCompleted) {
            log.info { "Received event: $event" }
        }
    }
}
