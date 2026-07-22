package io.bluetape4k.workshop.commerce.usagebilling.billing.domain

import io.bluetape4k.support.requireNotBlank
import java.math.BigDecimal
import java.util.UUID

data class BillingAdjustmentCommand(
    val adjustmentEventId: UUID,
    val tenantId: String,
    val correctionOf: UUID,
    val amount: BigDecimal,
    val currency: String,
) {
    init {
        tenantId.requireNotBlank("tenantId")
        currency.requireNotBlank("currency")
        require(amount.signum() < 0) { "adjustment amount must be negative" }
    }
}

enum class BillingAdjustmentOutcome {
    APPLIED,
    DUPLICATE,
}

interface BillingAdjustmentJournal {
    fun post(command: BillingAdjustmentCommand): BillingAdjustmentOutcome
}
