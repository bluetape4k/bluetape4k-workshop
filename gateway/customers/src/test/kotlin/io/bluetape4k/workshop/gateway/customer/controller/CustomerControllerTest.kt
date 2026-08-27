package io.bluetape4k.workshop.gateway.customer.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.gateway.customer.AbstractCustomerTest
import io.bluetape4k.workshop.gateway.customer.model.Customer
import io.bluetape4k.spring.tests.httpGet
import kotlinx.coroutines.reactive.awaitSingle
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult

class CustomerControllerTest: AbstractCustomerTest() {

    @Test
    fun `customer endpoint returns workshop customers`() = runSuspendIO {
        val customers = client
            .httpGet("/api/v1/customers")
            .expectStatus().is2xxSuccessful
            .returnResult<Customer>().responseBody
            .collectList()
            .awaitSingle()

        customers shouldHaveSize 2
        customers.map { it.name } shouldBeEqualTo listOf("Winter", "Spring")
    }
}
