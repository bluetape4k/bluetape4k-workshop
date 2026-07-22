@file:Suppress("LongParameterList") // A versioned integration envelope mirrors its fixed wire contract.

package io.bluetape4k.workshop.commerce.usagebilling.query.integration

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

class QueryIntegrationEnvelope private constructor(
    val eventId: UUID, val eventType: String, val schemaVersion: Int, val tenantId: String,
    val aggregateType: String, val aggregateId: String, val aggregateVersion: Long,
    val payload: String, val payloadDigest: String, val occurredAt: Instant, val recordedAt: Instant,
) {
    init {
        eventType.requireNotBlank("eventType")
        tenantId.requireNotBlank("tenantId")
        aggregateType.requireNotBlank("aggregateType")
        aggregateId.requireNotBlank("aggregateId")
        schemaVersion.requirePositiveNumber("schemaVersion")
        aggregateVersion.requirePositiveNumber("aggregateVersion")
        payload.requireNotBlank("payload")
    }

    fun partitionKey(): String = "$tenantId|$aggregateType|$aggregateId"
    fun hasValidPayloadDigest(): Boolean = payloadDigest == digestOf(payload)

    companion object {
        fun create(
            eventId: UUID, eventType: String, schemaVersion: Int, tenantId: String, aggregateType: String,
            aggregateId: String, aggregateVersion: Long, payload: String, occurredAt: Instant, recordedAt: Instant,
        ): QueryIntegrationEnvelope =
            QueryIntegrationEnvelope(
                eventId, eventType, schemaVersion, tenantId, aggregateType, aggregateId, aggregateVersion,
                payload, digestOf(payload), occurredAt, recordedAt,
            )

        private fun digestOf(payload: String): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(UTF_8)),
            )
    }
}

class QueryEnvelopeCodecRegistry {
    private val supportedSchemas = setOf(
        QueryEnvelopeSchema("PriceActivated", 1), QueryEnvelopeSchema("UsageAccepted", 1),
        QueryEnvelopeSchema("ChargeRated", 1), QueryEnvelopeSchema("AdjustmentPosted", 1),
        QueryEnvelopeSchema("InvoiceIssued", 1), QueryEnvelopeSchema("InvoiceCorrectionIssued", 1),
    )

    fun validate(envelope: QueryIntegrationEnvelope): QueryIntegrationEnvelope {
        if (!envelope.hasValidPayloadDigest()) throw InvalidQueryEnvelopeDigest(envelope.eventId)
        if (QueryEnvelopeSchema(envelope.eventType, envelope.schemaVersion) !in supportedSchemas) {
            throw UnsupportedQueryEnvelopeVersion(envelope.eventType, envelope.schemaVersion)
        }
        return envelope
    }
}

data class QueryEnvelopeSchema(
    val eventType: String,
    val schemaVersion: Int,
)

class UnsupportedQueryEnvelopeVersion(
    eventType: String,
    schemaVersion: Int,
) : IllegalArgumentException("unsupported_query_envelope:$eventType:$schemaVersion")

class InvalidQueryEnvelopeDigest(eventId: UUID) : IllegalArgumentException("invalid_query_envelope_digest:$eventId")
