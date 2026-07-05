package io.bluetape4k.workshop.gateway.orders.model

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderModelTest {

    @Test
    fun `order rejects blank identifiers and non-positive amounts`() {
        assertFailsWith<IllegalArgumentException> {
            Order("", BigDecimal("100.0"), "Winter")
        }

        assertFailsWith<IllegalArgumentException> {
            Order("O-1", BigDecimal.ZERO, "Winter")
        }

        assertFailsWith<IllegalArgumentException> {
            Order("O-1", BigDecimal("100.0"), "")
        }
    }

    @Test
    fun `product rejects blank identifiers and non-positive prices`() {
        assertFailsWith<IllegalArgumentException> {
            Product("", "Mac Book Pro", BigDecimal("230"))
        }

        assertFailsWith<IllegalArgumentException> {
            Product("P-1", "", BigDecimal("230"))
        }

        assertFailsWith<IllegalArgumentException> {
            Product("P-1", "Mac Book Pro", BigDecimal.ZERO)
        }
    }
}
