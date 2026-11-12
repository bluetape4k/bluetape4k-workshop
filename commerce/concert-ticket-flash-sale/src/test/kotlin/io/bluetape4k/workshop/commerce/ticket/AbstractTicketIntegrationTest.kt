package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture

internal abstract class AbstractTicketIntegrationTest {

    companion object: KLogging()

    protected fun PurchaseFixture.assertInventoryInvariant() {
        val total = queryInt("SELECT total_quantity FROM ticket_inventory WHERE sale_id = '$saleId' AND grade = 'GENERAL'")
        val held = queryInt("SELECT held_quantity FROM ticket_inventory WHERE sale_id = '$saleId' AND grade = 'GENERAL'")
        val sold = queryInt("SELECT sold_quantity FROM ticket_inventory WHERE sale_id = '$saleId' AND grade = 'GENERAL'")

        held shouldBeGreaterOrEqualTo 0
        sold shouldBeGreaterOrEqualTo 0
        total shouldBeGreaterOrEqualTo (held + sold)
    }

    protected fun PurchaseFixture.assertNoDuplicateEffects() {
        queryInt(
            "SELECT COUNT(*) FROM (SELECT operation_id FROM ticket_payment_operations GROUP BY operation_id HAVING COUNT(*) > 1) d",
        ) shouldBeEqualTo 0
        queryInt(
            "SELECT COUNT(*) FROM (SELECT operation_id FROM ticket_effect_receipts GROUP BY operation_id HAVING COUNT(*) > 1) d",
        ) shouldBeEqualTo 0
    }
}
