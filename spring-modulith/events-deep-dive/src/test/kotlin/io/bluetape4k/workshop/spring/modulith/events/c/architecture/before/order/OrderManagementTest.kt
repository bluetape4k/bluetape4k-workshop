package io.bluetape4k.workshop.spring.modulith.events.c.architecture.before.order

import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.MockkBeans
import io.bluetape4k.workshop.spring.modulith.events.c.architecture.before.inventory.Inventory
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.junit.jupiter.SpringExtension

@ExtendWith(SpringExtension::class)
@MockkBeans(
    MockkBean(Inventory::class, relaxed = true),
    MockkBean(OrderRepository::class, relaxed = true),
)
class OrderManagementTest(
    @Autowired private val inventory: Inventory,
    @Autowired private val orderRepository: OrderRepository,
) {
    val orders = OrderManagement(orderRepository, inventory)

    @Test
    fun `invoke Inventory update when order is completed`() {
        val order = Order()

        every { orderRepository.save(order) } returns order

        orders.completeOrder(order)

        verify(exactly = 1) { inventory.updateInventoryFor(order) }
    }
}
