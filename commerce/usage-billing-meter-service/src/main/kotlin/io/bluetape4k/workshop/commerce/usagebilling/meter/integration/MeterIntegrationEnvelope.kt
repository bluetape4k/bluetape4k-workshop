@file:Suppress("LongParameterList") // A versioned integration envelope mirrors its fixed wire contract.

package io.bluetape4k.workshop.commerce.usagebilling.meter.integration

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.nio.charset.StandardCharsets.UTF_8
import java.io.Serializable
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * Versioned Meter event contract persisted in the local outbox before Kafka publication.
 *
 * The envelope owns its digest so a consumer can reject tampered payloads without trusting transport metadata.
 */
class MeterIntegrationEnvelope private constructor(
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
) : Serializable {
    init {
        eventType.requireNotBlank("eventType")
        tenantId.requireNotBlank("tenantId")
        aggregateType.requireNotBlank("aggregateType")
        aggregateId.requireNotBlank("aggregateId")
        schemaVersion.requirePositiveNumber("schemaVersion")
        aggregateVersion.requirePositiveNumber("aggregateVersion")
        payload.requireNotBlank("payload")
        payloadDigest.requireNotBlank("payloadDigest")
    }

    fun partitionKey(): String = "$tenantId|$aggregateType|$aggregateId"

    fun hasValidPayloadDigest(): Boolean = payloadDigest == digestOf(payload)

    fun wirePayload(): String = Jackson.defaultJsonMapper.writeValueAsString(
        linkedMapOf(
            "eventId" to eventId.toString(),
            "eventType" to eventType,
            "schemaVersion" to schemaVersion,
            "tenantId" to tenantId,
            "aggregateType" to aggregateType,
            "aggregateId" to aggregateId,
            "aggregateVersion" to aggregateVersion,
            "payload" to payload,
            "payloadDigest" to payloadDigest,
            "occurredAt" to occurredAt.toString(),
            "recordedAt" to recordedAt.toString(),
        ),
    )

    fun wirePayloadDigest(): String = digestOf(wirePayload())

    companion object {
        const val AGGREGATE_TYPE = "Meter"
        private const val serialVersionUID: Long = 1L

        fun create(
            eventId: UUID,
            eventType: String,
            schemaVersion: Int,
            tenantId: String,
            aggregateId: String,
            aggregateVersion: Long,
            payload: String,
            occurredAt: Instant,
            recordedAt: Instant,
        ): MeterIntegrationEnvelope =
            MeterIntegrationEnvelope(
                eventId = eventId,
                eventType = eventType,
                schemaVersion = schemaVersion,
                tenantId = tenantId,
                aggregateType = AGGREGATE_TYPE,
                aggregateId = aggregateId,
                aggregateVersion = aggregateVersion,
                payload = payload,
                payloadDigest = digestOf(payload),
                occurredAt = occurredAt,
                recordedAt = recordedAt,
            )

        private fun digestOf(payload: String): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(UTF_8)),
            )
    }
}

class MeterEnvelopeCodecRegistry {
    private val supportedSchemas = setOf(
        MeterEnvelopeSchema("PriceActivated", 1),
        MeterEnvelopeSchema("PriceGapRepaired", 1),
    )

    fun validate(envelope: MeterIntegrationEnvelope): MeterIntegrationEnvelope {
        if (!envelope.hasValidPayloadDigest()) {
            throw InvalidMeterEnvelopeDigest(envelope.eventId)
        }
        if (MeterEnvelopeSchema(envelope.eventType, envelope.schemaVersion) !in supportedSchemas) {
            throw UnsupportedMeterEnvelopeVersion(envelope.eventType, envelope.schemaVersion)
        }
        return envelope
    }
}

data class MeterEnvelopeSchema(
    val eventType: String,
    val schemaVersion: Int,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

class UnsupportedMeterEnvelopeVersion(
    eventType: String,
    schemaVersion: Int,
) : IllegalArgumentException("unsupported_meter_envelope:$eventType:$schemaVersion")

class InvalidMeterEnvelopeDigest(eventId: UUID) : IllegalArgumentException("invalid_meter_envelope_digest:$eventId")
