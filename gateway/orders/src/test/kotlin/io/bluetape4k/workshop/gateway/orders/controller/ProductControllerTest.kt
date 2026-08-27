package io.bluetape4k.workshop.gateway.orders.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.gateway.orders.AbstractOrderTest
import io.bluetape4k.workshop.gateway.orders.model.Product
import io.bluetape4k.spring.tests.httpGet
import kotlinx.coroutines.reactive.awaitSingle
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult
import java.math.BigDecimal

class ProductControllerTest: AbstractOrderTest() {

    @Test
    fun `product endpoint returns workshop products`() = runSuspendIO {
        val products = client
            .httpGet("/api/v1/products")
            .expectStatus().is2xxSuccessful
            .returnResult<Product>().responseBody
            .collectList()
            .awaitSingle()

        products shouldHaveSize 2
        products.map { it.name } shouldBeEqualTo listOf("Mac Book Pro", "iPhone")
        products.map { it.price } shouldBeEqualTo listOf(BigDecimal("230"), BigDecimal("190"))
        products.all { it.id.isNotBlank() }.shouldBeTrue()
    }
}
