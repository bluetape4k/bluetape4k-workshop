package io.bluetape4k.workshop.commerce.usagebilling.billing.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class BillingInboxEvent(
    val eventId: UUID,
    val tenantId: String,
    val aggregateType: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val payloadDigest: String,
    val meterCode: String,
    val currency: String = "USD",
    val quantity: BigDecimal = BigDecimal.ONE,
) {
    init {
        tenantId.requireNotBlank("tenantId")
        aggregateType.requireNotBlank("aggregateType")
        aggregateId.requireNotBlank("aggregateId")
        aggregateVersion.requirePositiveNumber("aggregateVersion")
        payloadDigest.requireNotBlank("payloadDigest")
        meterCode.requireNotBlank("meterCode")
        currency.requireNotBlank("currency")
        quantity.requirePositiveNumber("quantity")
    }
}

data class BillingPriceEvidence(
    val tenantId: String,
    val meterCode: String,
    val currency: String,
    val unitPrice: BigDecimal,
    val effectiveAt: Instant,
) {
    init {
        tenantId.requireNotBlank("tenantId")
        meterCode.requireNotBlank("meterCode")
        currency.requireNotBlank("currency")
        unitPrice.requirePositiveNumber("unitPrice")
    }
}

enum class BillingInboxOutcome {
    APPLIED,
    DUPLICATE,
    DEFERRED,
    QUARANTINED,
}

interface BillingInboxJournal {
    val appliedEventIds: Set<UUID>

    fun priceEvidence(tenantId: String, meterCode: String, currency: String): BillingPriceEvidence?

    fun digestFor(eventId: UUID): String?

    fun expectedVersion(tenantId: String, aggregateId: String): Long

    fun apply(event: BillingInboxEvent)
}
