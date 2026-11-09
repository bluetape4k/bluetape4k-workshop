package io.bluetape4k.workshop.spring.modulith.events.c.architecture.before.order

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.spring.modulith.events.c.architecture.before.inventory.Inventory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OrderManagement(
    private val orderRepository: OrderRepository,
    private val inventory: Inventory,
) {

    companion object: KLogging()

    @Transactional
    fun completeOrder(order: Order) {
        order.complete()
        log.info { "Completing order. $order" }

        val saved = orderRepository.save(order)
        inventory.updateInventoryFor(saved)
    }
}
