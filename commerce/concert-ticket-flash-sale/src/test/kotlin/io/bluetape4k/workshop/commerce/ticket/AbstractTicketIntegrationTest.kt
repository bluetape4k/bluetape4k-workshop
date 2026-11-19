package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.purchase.PurchaseFixture

internal abstract class AbstractTicketIntegrationTest {

    companion object: KLogging()

    protected fun PurchaseFixture.assertInventoryInvariant() {
        val inventory = inventoryQuantities()

        inventory.held shouldBeGreaterOrEqualTo 0
        inventory.sold shouldBeGreaterOrEqualTo 0
        inventory.total shouldBeGreaterOrEqualTo (inventory.held + inventory.sold)
    }

    protected fun PurchaseFixture.assertNoDuplicateEffects() {
        duplicatePaymentOperationCount() shouldBeEqualTo 0
        duplicateEffectReceiptCount() shouldBeEqualTo 0
    }
}
