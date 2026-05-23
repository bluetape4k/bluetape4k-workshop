package io.bluetape4k.workshop.exposed.mvc.jdbc.order

import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.exposed.mvc.jdbc.AbstractMvcJdbcTest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.CreateProductRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.ProductDTO
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import java.math.BigDecimal

class ProductControllerTest : AbstractMvcJdbcTest() {

    @Test
    fun `GET products returns seeded products`() {
        val result = webTestClient.get()
            .uri("/api/v1/products")
            .exchange()
            .expectStatus().isOk
            .expectBodyList(ProductDTO::class.java)
            .returnResult()
        result.responseBody.shouldNotBeNull()
    }

    @Test
    fun `POST product creates product`() {
        val req = CreateProductRequest(
            name = "Test Product ${faker.number().digits(6)}",
            price = BigDecimal("19.99"),
            stock = 100
        )
        val result = webTestClient.post()
            .uri("/api/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .exchange()
            .expectStatus().isCreated
            .expectBody(ProductDTO::class.java)
            .returnResult()
        result.responseBody.shouldNotBeNull()
        result.responseBody!!.id shouldBeGreaterOrEqualTo 1L
    }
}
