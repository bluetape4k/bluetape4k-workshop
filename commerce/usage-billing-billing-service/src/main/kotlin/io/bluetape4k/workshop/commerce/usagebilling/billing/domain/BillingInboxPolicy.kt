package io.bluetape4k.workshop.commerce.usagebilling.billing.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.util.UUID

data class BillingInboxEvent(
    val eventId: UUID,
    val tenantId: String,
    val aggregateType: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val payloadDigest: String,
    val meterCode: String,
) {
    init {
        tenantId.requireNotBlank("tenantId")
        aggregateType.requireNotBlank("aggregateType")
        aggregateId.requireNotBlank("aggregateId")
        aggregateVersion.requirePositiveNumber("aggregateVersion")
        payloadDigest.requireNotBlank("payloadDigest")
        meterCode.requireNotBlank("meterCode")
    }
}

enum class BillingInboxOutcome {
    APPLIED,
    DUPLICATE,
    DEFERRED,
    QUARANTINED,
}

interface BillingInboxJournal {
    val pricingMeters: Set<String>
    val appliedEventIds: Set<UUID>

    fun digestFor(eventId: UUID): String?

    fun expectedVersion(tenantId: String, aggregateId: String): Long

    fun apply(event: BillingInboxEvent)
}
