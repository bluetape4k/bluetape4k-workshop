package io.bluetape4k.workshop.exposed.webflux.r2dbc.order

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.webflux.r2dbc.AbstractWebfluxR2dbcTest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.CreateProductRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.OrderLineRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.ProductDTO
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import java.math.BigDecimal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConcurrentPlaceOrderTest : AbstractWebfluxR2dbcTest() {

    companion object : KLoggingChannel()

    private fun createProduct(stock: Int): ProductDTO {
        val req = CreateProductRequest(
            name = faker.commerce().productName(),
            price = BigDecimal("5.00"),
            stock = stock,
        )
        return webTestClient.post().uri("/api/products")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated
            .expectBody(ProductDTO::class.java)
            .returnResult().responseBody.shouldNotBeNull()
    }

    private fun findProduct(id: Long): ProductDTO =
        webTestClient.get().uri("/api/products/$id")
            .exchange()
            .expectStatus().isOk
            .expectBody(ProductDTO::class.java)
            .returnResult().responseBody.shouldNotBeNull()

    @Test
    fun `concurrent orders deplete stock correctly without overselling`() = runSuspendIO {
        val totalStock = 10
        val concurrency = 20  // more requests than stock
        val product = createProduct(stock = totalStock)
        val statuses = ConcurrentLinkedQueue<Int>()
        val customerSeq = AtomicLong(1L)

        SuspendedJobTester()
            .workers(concurrency)
            .rounds(concurrency)
            .add {
                val req = PlaceOrderRequest(
                    customerId = customerSeq.getAndIncrement(),
                    lines = listOf(OrderLineRequest(productId = product.id, quantity = 1)),
                )
                val status = webTestClient.post().uri("/api/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .exchange()
                    .returnResult(String::class.java)
                    .status
                    .value()
                statuses.add(status)
            }
            .run()

        val successCount = statuses.count { it == 201 }
        val failCount = statuses.count { it == 409 }
        log.info { "Concurrent order results: success=$successCount, conflict=$failCount, total=${statuses.size}" }

        successCount shouldBeEqualTo totalStock
        failCount shouldBeEqualTo concurrency - totalStock
        (successCount + failCount) shouldBeEqualTo concurrency
        findProduct(product.id).stock shouldBeEqualTo 0
    }

    @Test
    fun `concurrent orders on multiple products do not deadlock`() = runSuspendIO {
        val productA = createProduct(stock = 50)
        val productB = createProduct(stock = 50)
        val concurrency = 10
        val statuses = ConcurrentLinkedQueue<Int>()
        val customerSeq = AtomicLong(1L)

        SuspendedJobTester()
            .workers(concurrency)
            .rounds(concurrency)
            .add {
                val i = customerSeq.getAndIncrement()
                val lines = if (i % 2L == 0L) {
                    listOf(
                        OrderLineRequest(productId = productA.id, quantity = 1),
                        OrderLineRequest(productId = productB.id, quantity = 1),
                    )
                } else {
                    listOf(
                        OrderLineRequest(productId = productB.id, quantity = 1),
                        OrderLineRequest(productId = productA.id, quantity = 1),
                    )
                }
                val req = PlaceOrderRequest(customerId = i, lines = lines)
                val status = webTestClient.post().uri("/api/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .exchange()
                    .returnResult(String::class.java)
                    .status
                    .value()
                statuses.add(status)
            }
            .run()

        val successCount = statuses.count { it == 201 }
        log.info { "Deadlock test results: success=$successCount / $concurrency" }
        successCount shouldBeEqualTo concurrency
    }
}
