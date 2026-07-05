package io.bluetape4k.workshop.gateway.customer.model

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test

class CustomerTest {

    @Test
    fun `customer rejects blank name`() {
        assertFailsWith<IllegalArgumentException> {
            Customer(" ")
        }
    }
}
