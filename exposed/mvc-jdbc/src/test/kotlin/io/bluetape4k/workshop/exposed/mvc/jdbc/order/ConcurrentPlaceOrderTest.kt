package io.bluetape4k.workshop.exposed.mvc.jdbc.order

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.workshop.exposed.mvc.jdbc.AbstractMvcJdbcTest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.OrderLineRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.ProductTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ConcurrentPlaceOrderTest : AbstractMvcJdbcTest() {

    @Autowired
    private lateinit var txManager: PlatformTransactionManager

    private fun <T: Any> inTx(block: () -> T): T =
        requireNotNull(TransactionTemplate(txManager).execute { block() }) {
            "Transaction callback returned null"
        }

    @Test
    fun `only one of N concurrent orders succeeds with stock=1`() {
        val testProductId = inTx {
            ProductTable.insert {
                it[name] = "Limited Edition ${faker.number().digits(8)}"
                it[price] = BigDecimal("99.99")
                it[stock] = 1
            }[ProductTable.id]
        }

        val N = 10
        val successCount = AtomicInteger(0)
        val conflictCount = AtomicInteger(0)
        val customerSeq = AtomicLong(1L)

        MultithreadingTester()
            .workers(N)
            .rounds(1)
            .add {
                val req = PlaceOrderRequest(
                    customerId = customerSeq.getAndIncrement(),
                    lines = listOf(OrderLineRequest(productId = testProductId, quantity = 1))
                )
                val status = webTestClient.post()
                    .uri("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .exchange()
                    .returnResult(String::class.java)
                    .status
                when {
                    status.is2xxSuccessful -> successCount.incrementAndGet()
                    status.value() == 409 -> conflictCount.incrementAndGet()
                }
            }
            .run()

        successCount.get() shouldBeEqualTo 1
        conflictCount.get() shouldBeEqualTo N - 1

        val finalStock = inTx {
            ProductTable.selectAll()
                .where { ProductTable.id eq testProductId }
                .single()[ProductTable.stock]
        }
        finalStock shouldBeEqualTo 0
    }
}
