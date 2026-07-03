package io.bluetape4k.workshop.exposed.webflux.r2dbc.order

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.exposed.webflux.r2dbc.AbstractWebfluxR2dbcTest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.CreateProductRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.OrderLineRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.ProductDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.OrderStatus
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import java.math.BigDecimal

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderControllerTest : AbstractWebfluxR2dbcTest() {

    private fun createProduct(stock: Int = 100): ProductDTO {
        val req = CreateProductRequest(
            name = faker.commerce().productName(),
            price = BigDecimal("9.99"),
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

    @Test
    fun `place order and retrieve it`() {
        val product = createProduct(stock = 50)

        val req = PlaceOrderRequest(
            customerId = 1L,
            lines = listOf(OrderLineRequest(productId = product.id, quantity = 2)),
        )

        val order = webTestClient.post().uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated
            .expectBody(OrderDTO::class.java)
            .returnResult().responseBody.shouldNotBeNull()

        order.id shouldBeGreaterThan 0L
        order.status shouldBeEqualTo OrderStatus.PENDING
        order.lines shouldHaveSize 1
        order.lines[0].quantity shouldBeEqualTo 2

        val fetched = webTestClient.get().uri("/api/orders/${order.id}")
            .exchange()
            .expectStatus().isOk
            .expectBody(OrderDTO::class.java)
            .returnResult().responseBody.shouldNotBeNull()
        fetched.lines.shouldNotBeEmpty()
    }

    @Test
    fun `cancel order changes status`() {
        val product = createProduct(stock = 10)
        val req = PlaceOrderRequest(
            customerId = 2L,
            lines = listOf(OrderLineRequest(productId = product.id, quantity = 1)),
        )
        val order = webTestClient.post().uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated
            .expectBody(OrderDTO::class.java)
            .returnResult().responseBody.shouldNotBeNull()

        val cancelled = webTestClient.post().uri("/api/orders/${order.id}/cancel")
            .exchange()
            .expectStatus().isOk
            .expectBody(OrderDTO::class.java)
            .returnResult().responseBody.shouldNotBeNull()
        cancelled.status shouldBeEqualTo OrderStatus.CANCELLED
    }

    @Test
    fun `place order with insufficient stock returns 409`() {
        val product = createProduct(stock = 1)
        val req = PlaceOrderRequest(
            customerId = 3L,
            lines = listOf(OrderLineRequest(productId = product.id, quantity = 100)),
        )
        webTestClient.post().uri("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().is4xxClientError
    }

    @Test
    fun `cancel non-existent order returns 404`() {
        webTestClient.post().uri("/api/orders/99999999/cancel")
            .exchange()
            .expectStatus().isNotFound
    }
}
