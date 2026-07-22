@file:Suppress("LongParameterList") // A versioned integration envelope mirrors its fixed wire contract.

package io.bluetape4k.workshop.commerce.usagebilling.usage.integration

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

class UsageIntegrationEnvelope private constructor(
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val tenantId: String,
    val aggregateType: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val payload: String,
    val payloadDigest: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
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
        ): UsageIntegrationEnvelope =
            UsageIntegrationEnvelope(
                eventId, eventType, schemaVersion, tenantId, "Usage", aggregateId, aggregateVersion,
                payload, digestOf(payload), occurredAt, recordedAt,
            )

        private fun digestOf(payload: String): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(UTF_8)),
            )
    }
}

class UsageEnvelopeCodecRegistry {
    private val supportedSchemas = setOf(
        UsageEnvelopeSchema("UsageAccepted", 1),
        UsageEnvelopeSchema("UsageRejected", 1),
        UsageEnvelopeSchema("UsageCorrected", 1),
    )

    fun validate(envelope: UsageIntegrationEnvelope): UsageIntegrationEnvelope {
        if (!envelope.hasValidPayloadDigest()) throw InvalidUsageEnvelopeDigest(envelope.eventId)
        if (UsageEnvelopeSchema(envelope.eventType, envelope.schemaVersion) !in supportedSchemas) {
            throw UnsupportedUsageEnvelopeVersion(envelope.eventType, envelope.schemaVersion)
        }
        return envelope
    }
}

data class UsageEnvelopeSchema(
    val eventType: String,
    val schemaVersion: Int,
)

class UnsupportedUsageEnvelopeVersion(
    eventType: String,
    schemaVersion: Int,
) : IllegalArgumentException("unsupported_usage_envelope:$eventType:$schemaVersion")

class InvalidUsageEnvelopeDigest(eventId: UUID) : IllegalArgumentException("invalid_usage_envelope_digest:$eventId")
