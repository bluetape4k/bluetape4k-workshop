package io.bluetape4k.workshop.commerce.usagebilling.invoice

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class InvoiceServiceApplicationContractTest {
    @Test
    fun `invoice service exposes its own boot application class`() {
        Class.forName(
            "io.bluetape4k.workshop.commerce.usagebilling.invoice.InvoiceServiceApplication",
        ).name shouldBeEqualTo
            "io.bluetape4k.workshop.commerce.usagebilling.invoice.InvoiceServiceApplication"
    }
}
