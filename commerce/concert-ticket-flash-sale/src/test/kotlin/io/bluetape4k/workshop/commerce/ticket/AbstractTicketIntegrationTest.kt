package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture

internal abstract class AbstractTicketIntegrationTest {
    protected fun PurchaseFixture.assertInventoryInvariant() {
        val total = queryInt("SELECT total_quantity FROM ticket_inventory WHERE sale_id = '$saleId' AND grade = 'GENERAL'")
        val held = queryInt("SELECT held_quantity FROM ticket_inventory WHERE sale_id = '$saleId' AND grade = 'GENERAL'")
        val sold = queryInt("SELECT sold_quantity FROM ticket_inventory WHERE sale_id = '$saleId' AND grade = 'GENERAL'")
        (held >= 0 && sold >= 0 && held + sold <= total) shouldBeEqualTo true
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
