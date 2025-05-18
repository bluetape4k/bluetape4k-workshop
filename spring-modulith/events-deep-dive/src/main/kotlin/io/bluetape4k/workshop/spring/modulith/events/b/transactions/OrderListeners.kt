package io.bluetape4k.workshop.spring.modulith.events.b.transactions

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

class OrderListeners {

    @Component
    class AnnotatedListener {
        companion object: KLogging()

        @EventListener
        fun on(event: Order.OrderCompleted) {
            log.info { "Received event: $event" }
        }
    }

    @Component
    class ConfigurableEventListener {
        companion object: KLogging()

        var fail: Boolean = false

        @EventListener
        fun on(event: Order.OrderCompleted) {
            log.info { "Received event: $event" }

            if (fail) {
                error("Error!")
            }
        }
    }

    @Component
    class TransactionalListener {
        companion object: KLogging()

        @TransactionalEventListener
        fun on(event: Order.OrderCompleted) {
            log.info { "Received event: $event" }
        }
    }

    @Component
    class ConfigurableTransactionalListener {
        companion object: KLogging()

        var fail: Boolean = false

        @TransactionalEventListener
        fun on(event: Order.OrderCompleted) {
            log.info { "Received event: $event" }

            if (fail) {
                error("Error!")
            }
        }
    }
}
