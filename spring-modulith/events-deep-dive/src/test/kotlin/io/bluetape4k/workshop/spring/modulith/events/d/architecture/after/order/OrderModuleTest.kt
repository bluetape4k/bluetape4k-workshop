package io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.order

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.order.Order.OrderCompleted
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.PublishedEvents

@ApplicationModuleTest
class OrderModuleTest(
    @Autowired private val orders: OrderManagement,
) {

    companion object: KLogging()

    @Test
    fun `publish OrderCompletedEvent`(events: PublishedEvents) {
        val order = Order()
        orders.completeOrder(order)

        val fired = events.ofType(OrderCompleted::class.java)
            .matching { it.order == order }

        fired.shouldNotBeEmpty()
    }
}
