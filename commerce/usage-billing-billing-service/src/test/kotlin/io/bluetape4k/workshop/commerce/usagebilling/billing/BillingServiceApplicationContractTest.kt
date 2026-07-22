package io.bluetape4k.workshop.commerce.usagebilling.billing

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class BillingServiceApplicationContractTest {
    @Test
    fun `billing service exposes its own boot application class`() {
        Class.forName("io.bluetape4k.workshop.commerce.usagebilling.billing.BillingServiceApplication").name shouldBeEqualTo
            "io.bluetape4k.workshop.commerce.usagebilling.billing.BillingServiceApplication"
    }
}
