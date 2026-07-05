package io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.quickstart

import com.ninjasquad.springmockk.MockkBean
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.events.util.IntegrationTest
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents

@IntegrationTest
@RecordApplicationEvents   // 어플리케이션 이벤트를 기록하기 위한 어노테이션
@MockkBean(types = [OrderRepository::class])
class OrderEventPublicationTests(
    @param:Autowired private val orders: OrderManagement,
    @param:Autowired private val repository: OrderRepository,
) {

    companion object : KLogging()

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
