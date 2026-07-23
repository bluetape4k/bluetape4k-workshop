package io.bluetape4k.workshop.commerce.usagebilling.usage.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requirePositiveNumber
import java.math.BigDecimal
import java.io.Serializable
import java.time.Instant
import java.util.UUID

data class AcceptUsageCommand(
    val tenantId: String,
    val sourceSystem: String,
    val sourceEventId: String,
    val meterCode: String,
    val currency: String,
    val quantity: BigDecimal,
    val occurredAt: Instant,
) : Serializable {
    init {
        tenantId.requireNotBlank("tenantId")
        sourceSystem.requireNotBlank("sourceSystem")
        sourceEventId.requireNotBlank("sourceEventId")
        meterCode.requireNotBlank("meterCode")
        currency.requireNotBlank("currency")
        quantity.requirePositiveNumber("quantity")
    }

    fun fingerprint(): String = "$meterCode|$currency|$quantity|$occurredAt"

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class PriceEvidence(
    val tenantId: String,
    val meterCode: String,
    val currency: String,
    val unitPrice: BigDecimal,
    val effectiveAt: Instant,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class PriceEvidenceInboxEvent(
    val eventId: UUID,
    val tenantId: String,
    val payloadDigest: String,
    val evidence: PriceEvidence,
) : Serializable {
    init {
        tenantId.requireNotBlank("tenantId")
        payloadDigest.requireNotBlank("payloadDigest")
        tenantId.requireEquals(evidence.tenantId, "evidence.tenantId")
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

enum class PriceEvidenceInboxOutcome {
    APPLIED,
    DUPLICATE,
    QUARANTINED,
}

data class UsageRecord(
    val usageId: UUID,
    val eventId: UUID,
    val tenantId: String,
    val sourceSystem: String,
    val sourceEventId: String,
    val fingerprint: String,
    val meterCode: String,
    val currency: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val occurredAt: Instant,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class UsageOutboxRecord(
    val eventId: UUID,
    val eventType: String,
    val partitionKey: String,
    val payload: String,
    val payloadDigest: String,
    val createdAt: Instant,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class UsageAcceptanceResult(
    val usageId: UUID,
    val eventId: UUID,
    val replayed: Boolean,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

interface UsageAcceptanceJournal {
    fun priceEvidence(tenantId: String, meterCode: String, currency: String): PriceEvidence?

    fun findUsage(tenantId: String, sourceSystem: String, sourceEventId: String): UsageRecord?

    fun append(usage: UsageRecord, outboxRecord: UsageOutboxRecord)
}

class MissingPriceEvidence : IllegalArgumentException("missing_price_evidence")

class UsageSourceConflict : IllegalArgumentException("usage_source_conflict")
