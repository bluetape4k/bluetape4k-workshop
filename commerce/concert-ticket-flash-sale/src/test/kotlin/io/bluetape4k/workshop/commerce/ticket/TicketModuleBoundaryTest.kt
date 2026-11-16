package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

internal class TicketModuleBoundaryTest {

    companion object: KLogging()

    @Test
    fun `modulith dependencies follow the approved graph`() {
        val modules = ApplicationModules.of(TicketFlashSaleApplication::class.java).verify()

        modules
            .map { it.identifier.toString() }
            .onEach { log.debug { "module=$it" } }
            .toSet() shouldBeEqualTo setOf("salecontrol", "admission", "purchase", "payment", "ticketing", "operations")
    }
}
