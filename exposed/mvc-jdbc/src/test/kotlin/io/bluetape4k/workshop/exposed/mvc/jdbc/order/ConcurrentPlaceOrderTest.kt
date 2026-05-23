package io.bluetape4k.workshop.exposed.mvc.jdbc.order

import io.bluetape4k.assertions.shouldBeEqualTo
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ConcurrentPlaceOrderTest : AbstractMvcJdbcTest() {

    @Autowired
    private lateinit var txManager: PlatformTransactionManager

    private fun <T> inTx(block: () -> T): T =
        TransactionTemplate(txManager).execute { block() }!!

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
        val latch = CountDownLatch(N)
        val executor = Executors.newFixedThreadPool(N)

        repeat(N) { i ->
            executor.submit {
                try {
                    val req = PlaceOrderRequest(
                        customerId = (i + 1).toLong(),
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
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        executor.shutdown()

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
