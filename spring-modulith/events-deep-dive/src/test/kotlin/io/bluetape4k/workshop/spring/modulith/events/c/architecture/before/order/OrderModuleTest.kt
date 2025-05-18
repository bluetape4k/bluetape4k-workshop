package io.bluetape4k.workshop.spring.modulith.events.c.architecture.before.order

import io.bluetape4k.workshop.spring.modulith.events.c.architecture.before.inventory.Inventory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest


@SpringBootTest
class OrderModuleTest {

    @Test
    fun `publishes order completed event`() {
        // given
        val order = Order()

        val orderRepository = mockk<OrderRepository>(relaxed = true)
        every { orderRepository.save(order) } returns order

        val inventory = mockk<Inventory>(relaxed = true)
        val orderManagement = OrderManagement(orderRepository, inventory)

        // when
        orderManagement.completeOrder(order)

        // then
        verify(exactly = 1) { orderRepository.save(order) }
        verify(exactly = 1) { inventory.updateInventoryFor(order) }
    }
}
