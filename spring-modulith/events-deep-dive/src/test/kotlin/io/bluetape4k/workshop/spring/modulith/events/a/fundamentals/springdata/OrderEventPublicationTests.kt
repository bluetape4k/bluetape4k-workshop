package io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.springdata

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.events.util.IntegrationTest
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents

@IntegrationTest
@RecordApplicationEvents
class OrderEventPublicationTests(
    @Autowired private val orders: OrderManagement,
) {

    companion object: KLogging()

    @Test
    fun `publish event on order completed`(events: ApplicationEvents) {
        orders.completeOrder(Order())

        events.stream(Order.OrderCompleted::class.java).toList() shouldHaveSize 1
    }
}
