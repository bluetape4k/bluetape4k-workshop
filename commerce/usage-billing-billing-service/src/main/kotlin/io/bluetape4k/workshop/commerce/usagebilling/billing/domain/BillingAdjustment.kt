package io.bluetape4k.workshop.commerce.usagebilling.billing.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNegativeNumber
import java.io.Serializable
import java.math.BigDecimal
import java.util.UUID

data class BillingAdjustmentCommand(
    val adjustmentEventId: UUID,
    val tenantId: String,
    val correctionOf: UUID,
    val amount: BigDecimal,
    val currency: String,
) : Serializable {
    init {
        tenantId.requireNotBlank("tenantId")
        currency.requireNotBlank("currency")
        amount.requireNegativeNumber("amount")
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

enum class BillingAdjustmentOutcome {
    APPLIED,
    DUPLICATE,
}

interface BillingAdjustmentJournal {
    fun post(command: BillingAdjustmentCommand): BillingAdjustmentOutcome
}
