package io.bluetape4k.workshop.gateway.orders.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.gateway.orders.AbstractOrderTest
import io.bluetape4k.workshop.gateway.orders.model.Order
import io.bluetape4k.workshop.shared.web.httpGet
import kotlinx.coroutines.reactive.awaitSingle
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult
import java.math.BigDecimal

class OrderControllerTest: AbstractOrderTest() {

    @Test
    fun `order endpoint returns workshop orders`() = runSuspendIO {
        val orders = client
            .httpGet("/api/v1/orders")
            .expectStatus().is2xxSuccessful
            .returnResult<Order>().responseBody
            .collectList()
            .awaitSingle()

        orders shouldHaveSize 2
        orders.map { it.customerName } shouldBeEqualTo listOf("Winter", "Spring")
        orders.map { it.amount } shouldBeEqualTo listOf(BigDecimal("100.0"), BigDecimal("50.0"))
        orders.all { it.orderNumber.isNotBlank() }.shouldBeTrue()
    }
}
