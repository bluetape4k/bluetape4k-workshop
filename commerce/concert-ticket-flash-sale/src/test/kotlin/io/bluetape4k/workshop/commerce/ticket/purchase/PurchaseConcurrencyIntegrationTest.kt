package io.bluetape4k.workshop.commerce.ticket.purchase

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.ActivePurchaseExists
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.InventoryUnavailable
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors

internal class PurchaseConcurrencyIntegrationTest {
    @Test
    fun `one buyer from two ips leaves one active attempt`() {
        PurchaseFixture(inventory = 2).use { fixture ->
            val buyer = Uuid.V7.nextId()
            val commands =
                listOf(
                    fixture.command(buyer = buyer, ip = Uuid.V7.nextId()),
                    fixture.command(buyer = buyer, ip = Uuid.V7.nextId()),
                )

            val results = concurrent(commands.map { { fixture.service.start(it) } })

            results.count { it.isSuccess } shouldBeEqualTo 1
            results.count { it.exceptionOrNull() is ActivePurchaseExists } shouldBeEqualTo 1
            fixture.guardCount("USER", buyer) shouldBeEqualTo 1
            fixture.inventoryHeld() shouldBeEqualTo 1
        }
    }

    @Test
    fun `two buyers sharing one ip leave one active attempt`() {
        PurchaseFixture(inventory = 2).use { fixture ->
            val commands = listOf(fixture.command(ip = fixture.sharedIp), fixture.command(ip = fixture.sharedIp))

            val results = concurrent(commands.map { { fixture.service.start(it) } })

            results.count { it.isSuccess } shouldBeEqualTo 1
            results.count { it.exceptionOrNull() is ActivePurchaseExists } shouldBeEqualTo 1
            fixture.guardCount("IP", fixture.sharedIp) shouldBeEqualTo 1
            fixture.inventoryHeld() shouldBeEqualTo 1
        }
    }

    @Test
    fun `two distinct buyers competing for last inventory have one winner and no oversell`() {
        PurchaseFixture(inventory = 1).use { fixture ->
            val commands =
                listOf(
                    fixture.command(ip = Uuid.V7.nextId()),
                    fixture.command(ip = Uuid.V7.nextId()),
                )

            val results = concurrent(commands.map { { fixture.service.start(it) } })

            results.count { it.isSuccess } shouldBeEqualTo 1
            results.count { it.exceptionOrNull() is InventoryUnavailable } shouldBeEqualTo 1
            fixture.inventoryHeld() shouldBeEqualTo 1
        }
    }

    private fun concurrent(tasks: List<() -> Any>): List<Result<Any>> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            tasks.map { task -> executor.submit<Result<Any>> { runCatching(task) } }.map { it.get() }
        }
}

private fun PurchaseFixture.guardCount(kind: String, subjectId: UUID): Int =
    queryInt(
        "SELECT COUNT(*) FROM ticket_active_identity_guards " +
            "WHERE sale_id = '$saleId' AND identity_kind = '$kind' AND identity_subject_id = '$subjectId'",
    )

private fun PurchaseFixture.inventoryHeld(): Int =
    queryInt("SELECT held_quantity FROM ticket_inventory WHERE sale_id = '$saleId' AND grade = 'GENERAL'")
