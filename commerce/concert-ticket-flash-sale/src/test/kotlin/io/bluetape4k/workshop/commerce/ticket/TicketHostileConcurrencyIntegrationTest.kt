package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

internal class TicketHostileConcurrencyIntegrationTest : AbstractTicketIntegrationTest() {
    @Test
    fun `many buyers racing for the last seats never oversell`() {
        PurchaseFixture(inventory = 4).use { fixture ->
            val commands = List(24) { fixture.command(ip = Uuid.V7.nextId()) }
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                commands.map { command -> executor.submit { runCatching { fixture.service.start(command) } } }
                    .forEach { it.get() }
            }
            fixture.assertInventoryInvariant()
            fixture.assertNoDuplicateEffects()
        }
    }
}
