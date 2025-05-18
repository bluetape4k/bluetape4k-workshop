package io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.quickstart

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class OrderManagement(
    private val orderRepository: OrderRepository,
    private val publisher: ApplicationEventPublisher,
) {

    companion object: KLogging()

    fun completeOrder(order: Order) {
        publisher.publishEvent(SomeOtherEvent())

        order.complete()

        log.info { "Completing order. order=$order" }

        orderRepository.save(order)
        publisher.publishEvent(OrderCompleted(order))
    }

    class OrderCompleted(source: Any): ApplicationEvent(source) {
        override fun toString(): String = "OrderCompleted(source=$source)"
    }

    class SomeOtherEvent() {}
}
