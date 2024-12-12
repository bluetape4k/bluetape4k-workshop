package io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.inventory

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.order.Order
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class Inventory {

    companion object: KLogging()

    var fail = false

    @EventListener
    fun on(event: Order.OrderCompleted) {
        updateInventoryFor(event.order)
    }

    fun updateInventoryFor(order: Order) {
        log.info { "Updating inventory for order. $order" }

        if (fail) {
            error("Error!")
        }
    }
}
