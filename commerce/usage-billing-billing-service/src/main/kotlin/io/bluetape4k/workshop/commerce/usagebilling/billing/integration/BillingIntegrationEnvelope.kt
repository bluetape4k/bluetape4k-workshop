package io.bluetape4k.workshop.commerce.usagebilling.billing.integration

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

class BillingIntegrationEnvelope private constructor(
    val eventId: UUID, val eventType: String, val schemaVersion: Int, val tenantId: String,
    val aggregateType: String, val aggregateId: String, val aggregateVersion: Long,
    val payload: String, val payloadDigest: String, val occurredAt: Instant, val recordedAt: Instant,
) {
    init {
        eventType.requireNotBlank("eventType")
        tenantId.requireNotBlank("tenantId")
        aggregateId.requireNotBlank("aggregateId")
        schemaVersion.requirePositiveNumber("schemaVersion")
        aggregateVersion.requirePositiveNumber("aggregateVersion")
        payload.requireNotBlank("payload")
    }

    fun partitionKey(): String = "$tenantId|$aggregateType|$aggregateId"
    fun hasValidPayloadDigest(): Boolean = payloadDigest == digestOf(payload)

    companion object {
        fun create(
            eventId: UUID, eventType: String, schemaVersion: Int, tenantId: String, aggregateId: String,
            aggregateVersion: Long, payload: String, occurredAt: Instant, recordedAt: Instant,
        ): BillingIntegrationEnvelope =
            BillingIntegrationEnvelope(
                eventId, eventType, schemaVersion, tenantId, "BillingPeriod", aggregateId, aggregateVersion,
                payload, digestOf(payload), occurredAt, recordedAt,
            )

        private fun digestOf(payload: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(UTF_8)))
    }
}

class BillingEnvelopeCodecRegistry {
    private val supportedSchemas = setOf(BillingEnvelopeSchema("ChargeRated", 1), BillingEnvelopeSchema("AdjustmentPosted", 1), BillingEnvelopeSchema("BillingPeriodClosed", 1))

    fun validate(envelope: BillingIntegrationEnvelope): BillingIntegrationEnvelope {
        if (!envelope.hasValidPayloadDigest()) throw InvalidBillingEnvelopeDigest(envelope.eventId)
        if (BillingEnvelopeSchema(envelope.eventType, envelope.schemaVersion) !in supportedSchemas) {
            throw UnsupportedBillingEnvelopeVersion(envelope.eventType, envelope.schemaVersion)
        }
        return envelope
    }
}

data class BillingEnvelopeSchema(val eventType: String, val schemaVersion: Int)
class UnsupportedBillingEnvelopeVersion(eventType: String, schemaVersion: Int) : IllegalArgumentException("unsupported_billing_envelope:$eventType:$schemaVersion")
class InvalidBillingEnvelopeDigest(eventId: UUID) : IllegalArgumentException("invalid_billing_envelope_digest:$eventId")
