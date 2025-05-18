package io.bluetape4k.workshop.spring.modulith.events.b.transactions

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import jakarta.transaction.Transactional
import org.springframework.stereotype.Component

@Component
class OrderManagement(
    private val orderRepository: OrderRepository,
) {
    companion object: KLogging()

    @Transactional
    fun completeOrder(order: Order) {
        log.info { "Completing order. order=$order" }
        orderRepository.save(order.complete())
    }

    @Transactional
    fun failToCompleteOrder(order: Order) {
        log.info { "Completing order. order=$order" }
        orderRepository.save(order.complete())
        error("Error!")
    }
}
