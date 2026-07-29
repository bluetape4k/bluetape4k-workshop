package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

internal class TicketModuleBoundaryTest {
    @Test
    fun `modulith dependencies follow the approved graph`() {
        val modules = ApplicationModules.of(TicketFlashSaleApplication::class.java).verify()

        modules.stream().map { it.identifier.toString() }.toList().toSet() shouldBeEqualTo
            setOf("salecontrol", "admission", "purchase", "payment", "ticketing", "operations")
    }
}
