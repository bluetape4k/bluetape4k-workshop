@file:Suppress("LongParameterList") // A versioned integration envelope mirrors its fixed wire contract.

package io.bluetape4k.workshop.commerce.usagebilling.usage.integration

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.shared.integration.AggregatePartitionKey
import io.bluetape4k.workshop.commerce.shared.integration.PayloadDigest
import java.io.Serializable
import java.time.Instant
import java.util.*

/**
 * Kafka publication 전에 local outbox에 저장되는 Versioned Usage event contract입니다.
 *
 * payload digest는 durable contract의 일부이며 각 receiving service가 검증합니다.
 */
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
): Serializable {

    companion object: KLogging() {
        private const val serialVersionUID: Long = 1L

        operator fun invoke(
            eventId: UUID,
            eventType: String,
            schemaVersion: Int,
            tenantId: String,
            aggregateId: String,
            aggregateVersion: Long,
            payload: String,
            occurredAt: Instant,
            recordedAt: Instant,
        ): UsageIntegrationEnvelope =
            UsageIntegrationEnvelope(
                eventId,
                eventType,
                schemaVersion,
                tenantId,
                "Usage",
                aggregateId,
                aggregateVersion,
                payload,
                digestOf(payload),
                occurredAt,
                recordedAt,
            )

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
        ): UsageIntegrationEnvelope =
            invoke(
                eventId,
                eventType,
                schemaVersion,
                tenantId,
                aggregateId,
                aggregateVersion,
                payload,
                occurredAt,
                recordedAt,
            )

        private fun digestOf(payload: String): String = PayloadDigest.of(payload)
    }

    init {
        eventType.requireNotBlank("eventType")
        tenantId.requireNotBlank("tenantId")
        aggregateId.requireNotBlank("aggregateId")
        schemaVersion.requirePositiveNumber("schemaVersion")
        aggregateVersion.requirePositiveNumber("aggregateVersion")
        payload.requireNotBlank("payload")
    }

    fun partitionKey(): String = AggregatePartitionKey.of(tenantId, aggregateType, aggregateId)

    fun hasValidPayloadDigest(): Boolean = PayloadDigest.matches(payload, payloadDigest)

    fun wirePayload(): String =
        Jackson.defaultJsonMapper.writeValueAsString(
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
}

class UsageEnvelopeCodecRegistry {

    companion object: KLogging()

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
): Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

class UnsupportedUsageEnvelopeVersion(
    eventType: String,
    schemaVersion: Int,
): IllegalArgumentException("unsupported_usage_envelope:$eventType:$schemaVersion")

class InvalidUsageEnvelopeDigest(eventId: UUID): IllegalArgumentException("invalid_usage_envelope_digest:$eventId")
