package io.bluetape4k.workshop.spring.modulith.ddd.audit.orders

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldHaveSize
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderDomainTest {

    @Test
    fun `rejects empty order lines`() {
        assertFailsWith<IllegalArgumentException> {
            Order.place(PlaceOrderCommand(CustomerId("customer-1"), emptyList()))
        }
    }

    @Test
    fun `place creates immutable aggregate and safe domain event`() {
        val order = Order.place(validPlaceOrderCommand())

        order.status shouldBeEqualTo OrderStatus.PLACED
        order.lines shouldHaveSize 1
        order.events shouldHaveSize 1
        order.events.single().shouldBeInstanceOf<OrderPlaced>()
        order.events.single().aggregateId shouldBeEqualTo order.id.value
    }

    @Test
    fun `approve creates new aggregate and domain event`() {
        val order = Order.place(validPlaceOrderCommand())
        val approved = order.approve(ApproveOrderCommand(order.id, approvedBy = "ops-user"))

        approved.status shouldBeEqualTo OrderStatus.APPROVED
        approved.events shouldHaveSize 1
        approved.events.single().shouldBeInstanceOf<OrderApproved>()
        approved.events.single().aggregateId shouldBeEqualTo order.id.value
        order.status shouldBeEqualTo OrderStatus.PLACED
    }

    @Test
    fun `rejects repeated approve`() {
        val placed = Order.place(validPlaceOrderCommand())
        val approved = placed.approve(ApproveOrderCommand(placed.id, approvedBy = "ops-user"))

        assertFailsWith<IllegalStateException> {
            approved.approve(ApproveOrderCommand(approved.id, approvedBy = "ops-user"))
        }
    }

    private fun validPlaceOrderCommand(): PlaceOrderCommand =
        PlaceOrderCommand(
            customerId = CustomerId("customer-1"),
            lines = listOf(
                OrderLine(
                    sku = "sku-1",
                    quantity = 2,
                    unitPrice = Money(BigDecimal("12.50"), "USD"),
                ),
            ),
        )
}
