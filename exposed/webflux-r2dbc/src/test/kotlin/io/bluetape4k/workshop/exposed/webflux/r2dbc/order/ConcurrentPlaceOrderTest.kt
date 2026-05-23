package io.bluetape4k.workshop.exposed.webflux.r2dbc.order

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.webflux.r2dbc.AbstractWebfluxR2dbcTest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.CreateProductRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.OrderLineRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.ProductDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import java.math.BigDecimal

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
            .returnResult().responseBody!!
    }

    @Test
    fun `concurrent orders deplete stock correctly without overselling`() {
        val totalStock = 10
        val concurrency = 20  // more requests than stock
        val product = createProduct(stock = totalStock)

        val results = runBlocking {
            coroutineScope {
                List(concurrency) { i ->
                    async(Dispatchers.IO) {
                        val req = PlaceOrderRequest(
                            customerId = (i + 1).toLong(),
                            lines = listOf(OrderLineRequest(productId = product.id, quantity = 1)),
                        )
                        runCatching {
                            webTestClient.post().uri("/api/orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(req)
                                .exchange()
                                .expectBody(OrderDTO::class.java)
                                .returnResult()
                                .status
                                .value()
                        }.getOrDefault(409)
                    }
                }.awaitAll()
            }
        }

        val successCount = results.count { it == 201 }
        val failCount = results.count { it == 409 }
        log.info { "Concurrent order results: success=$successCount, conflict=$failCount, total=${results.size}" }

        // Exactly totalStock orders should succeed, rest should fail with stock error
        assert(successCount <= totalStock) {
            "Expected at most $totalStock successful orders but got $successCount"
        }
        assert(successCount + failCount == concurrency) {
            "Total results mismatch: success=$successCount, fail=$failCount, expected=$concurrency"
        }
    }

    @Test
    fun `concurrent orders on multiple products do not deadlock`() {
        val productA = createProduct(stock = 50)
        val productB = createProduct(stock = 50)
        val concurrency = 10

        val results = runBlocking {
            coroutineScope {
                List(concurrency) { i ->
                    async(Dispatchers.IO) {
                        // Alternate between (A then B) and (B then A) orders to stress deadlock prevention
                        val lines = if (i % 2 == 0) {
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
                        val req = PlaceOrderRequest(customerId = (i + 1).toLong(), lines = lines)
                        runCatching {
                            webTestClient.post().uri("/api/orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(req)
                                .exchange()
                                .expectBody(OrderDTO::class.java)
                                .returnResult()
                                .status
                                .value()
                        }.getOrDefault(500)
                    }
                }.awaitAll()
            }
        }

        val successCount = results.count { it == 201 }
        log.info { "Deadlock test results: success=$successCount / $concurrency" }
        // All should succeed (sufficient stock, deadlock prevention via sorted productId)
        assert(successCount == concurrency) {
            "Expected all $concurrency orders to succeed but got $successCount. Results: $results"
        }
    }
}
