package io.bluetape4k.workshop.commerce.usagebilling.meter.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.math.BigDecimal
import java.io.Serializable
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

data class ActivatePriceCommand(
    val idempotencyKey: String,
    val tenantId: String,
    val meterCode: String,
    val currency: String,
    val unitPrice: BigDecimal,
    val effectiveAt: Instant,
) : Serializable {
    init {
        idempotencyKey.requireNotBlank("idempotencyKey")
        tenantId.requireNotBlank("tenantId")
        meterCode.requireNotBlank("meterCode")
        currency.requireNotBlank("currency")
        unitPrice.requirePositiveNumber("unitPrice")
    }

    fun fingerprint(): String = digestOf("$tenantId|$meterCode|$currency|$unitPrice|$effectiveAt")

    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class MeterPriceVersion(
    val priceVersionId: UUID,
    val tenantId: String,
    val meterCode: String,
    val currency: String,
    val unitPrice: BigDecimal,
    val effectiveAt: Instant,
    val createdAt: Instant,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class MeterOutboxRecord(
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

data class MeterActivationResult(
    val priceVersionId: UUID,
    val eventId: UUID,
    val replayed: Boolean,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class MeterCommandReceipt(
    val idempotencyKey: String,
    val fingerprint: String,
    val result: MeterActivationResult,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

interface MeterCommandJournal {
    fun findReceipt(idempotencyKey: String): MeterCommandReceipt?

    fun append(
        receipt: MeterCommandReceipt,
        priceVersion: MeterPriceVersion,
        outboxRecord: MeterOutboxRecord,
    )
}

class MeterIdempotencyConflict : IllegalArgumentException("meter_idempotency_conflict")
