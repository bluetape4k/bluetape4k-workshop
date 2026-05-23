package io.bluetape4k.workshop.exposed.mvc.vt.order

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.exposed.mvc.vt.AbstractMvcVirtualThreadTest
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.OrderLineRequest
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.OrderLineTable
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.OrderTable
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.ProductTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType

class PlaceOrderRollbackTest : AbstractMvcVirtualThreadTest() {

    @Autowired
    private lateinit var db: Database

    private fun <T> inTx(block: () -> T): T = transaction(db) { block() }

    @Test
    fun `failed order with invalid product rolls back all tables`() {
        val productId = firstProductId()

        val originalStock = inTx {
            ProductTable.selectAll()
                .where { ProductTable.id eq productId }
                .single()[ProductTable.stock]
        }
        val orderCountBefore = inTx { OrderTable.selectAll().count() }
        val lineCountBefore = inTx { OrderLineTable.selectAll().count() }

        val req = PlaceOrderRequest(
            customerId = 1L,
            lines = listOf(
                OrderLineRequest(productId = productId, quantity = 1),
                OrderLineRequest(productId = 99999L, quantity = 1),  // nonexistent
            )
        )
        webTestClient.post()
            .uri("/api/v1/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isNotFound

        inTx {
            OrderTable.selectAll().count() shouldBeEqualTo orderCountBefore
            OrderLineTable.selectAll().count() shouldBeEqualTo lineCountBefore
            val finalStock = ProductTable.selectAll()
                .where { ProductTable.id eq productId }
                .single()[ProductTable.stock]
            finalStock shouldBeEqualTo originalStock
        }
    }
}
