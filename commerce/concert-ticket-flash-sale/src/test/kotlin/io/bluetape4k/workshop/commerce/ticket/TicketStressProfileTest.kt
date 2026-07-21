package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors

internal class TicketStressProfileTest : AbstractTicketIntegrationTest() {
    @Tag("stress")
    @Test
    fun `same grade contention records invariant evidence`() {
        checkNotNull(System.getProperty("ticket.stress.run"))
        PurchaseFixture(inventory = 25).use { fixture ->
            val commands = List(200) { fixture.command(ip = UUID.randomUUID()) }
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                commands.map { executor.submit { runCatching { fixture.service.start(it) } } }.forEach { it.get() }
            }
            fixture.assertInventoryInvariant()
            fixture.assertNoDuplicateEffects()
        }
    }
}
