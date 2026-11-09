package io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.quickstart

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.context.ApplicationListener
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

class OrderListeners {

    @Component
    class ImplementingListener: ApplicationListener<OrderManagement.OrderCompleted> {

        companion object: KLogging()

        override fun onApplicationEvent(event: OrderManagement.OrderCompleted) {
            log.info { "Received event: $event" }
        }
    }

    @Component
    class AnnotatedListener {

        companion object: KLogging()

        @EventListener
        fun on(event: OrderManagement.OrderCompleted) {
            log.info { "Received event: $event" }
        }

        @EventListener
        fun on(event: OrderManagement.SomeOtherEvent) {
            log.info { "Received event: $event" }
        }
    }
}
