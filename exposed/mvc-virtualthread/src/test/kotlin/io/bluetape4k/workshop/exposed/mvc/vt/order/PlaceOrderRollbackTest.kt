package io.bluetape4k.workshop.exposed.mvc.vt.order

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.exposed.mvc.vt.AbstractMvcVirtualThreadTest
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.OrderLineRequest
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.OrderLineTable
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.OrderTable
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.ProductTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class PlaceOrderRollbackTest: AbstractMvcVirtualThreadTest() {

    @Test
    fun `failed order with invalid product rolls back all tables`() {
        val productId = firstProductId()

        val originalStock = withTx {
            ProductTable.selectAll()
                .where { ProductTable.id eq productId }
                .single()[ProductTable.stock]
        }
        val orderCountBefore = withTx { OrderTable.selectAll().count() }
        val lineCountBefore = withTx { OrderLineTable.selectAll().count() }

        val req = PlaceOrderRequest(
            customerId = 1L,
            lines = listOf(
                OrderLineRequest(productId = productId, quantity = 1),
                OrderLineRequest(productId = 99999L, quantity = 1),  // 존재하지 않는 상품이다.
            )
        )
        webTestClient.post()
            .uri("/api/v1/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isNotFound

        withTx {
            OrderTable.selectAll().count() shouldBeEqualTo orderCountBefore
            OrderLineTable.selectAll().count() shouldBeEqualTo lineCountBefore
            val finalStock = ProductTable.selectAll()
                .where { ProductTable.id eq productId }
                .single()[ProductTable.stock]
            finalStock shouldBeEqualTo originalStock
        }
    }
}
