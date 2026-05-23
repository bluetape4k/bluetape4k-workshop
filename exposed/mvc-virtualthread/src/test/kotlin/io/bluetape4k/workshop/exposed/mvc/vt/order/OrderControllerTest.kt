package io.bluetape4k.workshop.exposed.mvc.vt.order

import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.exposed.mvc.vt.AbstractMvcVirtualThreadTest
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.OrderLineRequest
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.PlaceOrderRequest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

class OrderControllerTest : AbstractMvcVirtualThreadTest() {

    @Test
    fun `POST orders places order successfully`() {
        val productId = firstProductId()
        val req = PlaceOrderRequest(
            customerId = 1L,
            lines = listOf(OrderLineRequest(productId = productId, quantity = 1))
        )
        val result = webTestClient.post()
            .uri("/api/v1/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated
            .expectBody(OrderDTO::class.java)
            .returnResult()
        result.responseBody.shouldNotBeNull()
        result.responseBody!!.id shouldBeGreaterOrEqualTo 1L
    }

    @Test
    fun `POST orders with nonexistent product returns 404`() {
        val req = PlaceOrderRequest(
            customerId = 1L,
            lines = listOf(OrderLineRequest(productId = 99999L, quantity = 1))
        )
        webTestClient.post()
            .uri("/api/v1/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `PATCH cancel order returns cancelled order`() {
        val productId = secondProductId()
        val placeReq = PlaceOrderRequest(
            customerId = 1L,
            lines = listOf(OrderLineRequest(productId = productId, quantity = 1))
        )
        val order = webTestClient.post()
            .uri("/api/v1/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(placeReq)
            .exchange()
            .expectStatus().isCreated
            .expectBody(OrderDTO::class.java)
            .returnResult().responseBody!!

        webTestClient.patch()
            .uri("/api/v1/orders/${order.id}/cancel")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `PATCH cancel nonexistent order returns 404`() {
        webTestClient.patch()
            .uri("/api/v1/orders/99999/cancel")
            .exchange()
            .expectStatus().isNotFound
    }
}
