package io.bluetape4k.workshop.spring.modulith.events.c.architecture.before.inventory

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.spring.modulith.events.c.architecture.before.order.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class Inventory {

    companion object: KLogging()

    @Transactional
    fun updateInventoryFor(order: Order) {
        log.info { "Update inventory for order. $order" }
    }
}
