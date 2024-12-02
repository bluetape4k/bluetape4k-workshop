package io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.quickstart

import com.ninjasquad.springmockk.MockkBean
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.events.util.IntegrationTest
import io.mockk.every
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.BeforeEach
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

    @MockkBean
    private lateinit var repository: OrderRepository

    @BeforeEach
    fun setup() {
        every { repository.save(any()) } answers { firstArg() }
    }

    @Test
    fun `publish event on completion`(events: ApplicationEvents) {
        orders.completeOrder(Order())

        events.stream(OrderManagement.SomeOtherEvent::class.java).toList() shouldHaveSize 1
        events.stream(OrderManagement.OrderCompleted::class.java).toList() shouldHaveSize 1
    }
}
