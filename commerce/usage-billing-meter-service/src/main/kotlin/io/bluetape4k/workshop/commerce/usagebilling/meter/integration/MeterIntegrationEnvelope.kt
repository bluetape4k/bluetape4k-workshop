package io.bluetape4k.workshop.commerce.usagebilling.meter.integration

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

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
) {
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

    companion object {
        const val AGGREGATE_TYPE = "Meter"

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
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(UTF_8)))
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
)

class UnsupportedMeterEnvelopeVersion(
    eventType: String,
    schemaVersion: Int,
) : IllegalArgumentException("unsupported_meter_envelope:$eventType:$schemaVersion")

class InvalidMeterEnvelopeDigest(eventId: UUID) : IllegalArgumentException("invalid_meter_envelope_digest:$eventId")
