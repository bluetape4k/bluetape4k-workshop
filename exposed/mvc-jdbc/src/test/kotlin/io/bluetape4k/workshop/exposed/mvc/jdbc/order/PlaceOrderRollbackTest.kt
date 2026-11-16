package io.bluetape4k.workshop.exposed.mvc.jdbc.order

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.mvc.jdbc.AbstractMvcJdbcTest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.OrderLineRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.OrderLineTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.OrderTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.ProductTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class PlaceOrderRollbackTest: AbstractMvcJdbcTest() {

    companion object: KLogging()

    @Autowired
    private lateinit var txManager: PlatformTransactionManager

    private inline fun <T: Any> withTx(crossinline block: () -> T): T =
        TransactionTemplate(txManager).execute { block() }.shouldNotBeNull()

    @Test
    fun `failed order with invalid product rolls back all tables`() {
        val originalStock = withTx {
            ProductTable.selectAll()
                .where { ProductTable.id eq 1L }
                .single()[ProductTable.stock]
        }
        val orderCountBefore = withTx { OrderTable.selectAll().count() }
        val lineCountBefore = withTx { OrderLineTable.selectAll().count() }

        val req = PlaceOrderRequest(
            customerId = 1L,
            lines = listOf(
                OrderLineRequest(productId = 1L, quantity = 1),
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
                .where { ProductTable.id eq 1L }
                .single()[ProductTable.stock]
            finalStock shouldBeEqualTo originalStock
        }
    }
}
