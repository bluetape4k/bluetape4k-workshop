package io.bluetape4k.workshop.commerce.usagebilling.meter

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class MeterServiceApplicationContractTest {
    @Test
    fun `meter service exposes its own boot application class`() {
        Class.forName("io.bluetape4k.workshop.commerce.usagebilling.meter.MeterServiceApplication").name shouldBeEqualTo
            "io.bluetape4k.workshop.commerce.usagebilling.meter.MeterServiceApplication"
    }
}
