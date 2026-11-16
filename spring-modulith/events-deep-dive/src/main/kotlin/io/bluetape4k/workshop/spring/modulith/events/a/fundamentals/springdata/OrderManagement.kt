package io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.springdata

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.stereotype.Component

@Component
class OrderManagement(
    private val orderRepository: OrderRepository,
) {

    companion object: KLogging()

    fun completeOrder(order: Order) {
        log.info { "Completing order. order=$order" }
        orderRepository.save(order.complete())
    }
}
