package io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.order

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OrderManagement(
    private val orderRepository: OrderRepository,
) {
    companion object: KLogging()

    @Transactional
    fun completeOrder(order: Order) {
        log.info { "Completing order. $order" }
        orderRepository.save(order.complete())
    }
}
