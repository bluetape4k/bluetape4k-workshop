package io.bluetape4k.workshop.idempotency.model

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test

class OrderModelsTest {

    @Test
    fun `order request rejects blank product id`() {
        assertFailsWith<IllegalArgumentException> {
            OrderRequest(
                productId = " ",
                quantity = 1,
                userId = "user-123",
            )
        }
    }

    @Test
    fun `order request rejects non-positive quantity`() {
        assertFailsWith<IllegalArgumentException> {
            OrderRequest(
                productId = "prod-001",
                quantity = 0,
                userId = "user-123",
            )
        }
    }

    @Test
    fun `order request rejects blank user id`() {
        assertFailsWith<IllegalArgumentException> {
            OrderRequest(
                productId = "prod-001",
                quantity = 1,
                userId = " ",
            )
        }
    }
}
