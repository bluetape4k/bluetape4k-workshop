package io.bluetape4k.workshop.commerce.usagebilling.invoice.integration

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

class InvoiceIntegrationEnvelope private constructor(
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
        ): InvoiceIntegrationEnvelope =
            InvoiceIntegrationEnvelope(
                eventId, eventType, schemaVersion, tenantId, "Invoice", aggregateId, aggregateVersion,
                payload, digestOf(payload), occurredAt, recordedAt,
            )

        private fun digestOf(payload: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(UTF_8)))
    }
}

class InvoiceEnvelopeCodecRegistry {
    private val supportedSchemas = setOf(InvoiceEnvelopeSchema("InvoiceIssued", 1), InvoiceEnvelopeSchema("InvoiceCorrectionIssued", 1))

    fun validate(envelope: InvoiceIntegrationEnvelope): InvoiceIntegrationEnvelope {
        if (!envelope.hasValidPayloadDigest()) throw InvalidInvoiceEnvelopeDigest(envelope.eventId)
        if (InvoiceEnvelopeSchema(envelope.eventType, envelope.schemaVersion) !in supportedSchemas) {
            throw UnsupportedInvoiceEnvelopeVersion(envelope.eventType, envelope.schemaVersion)
        }
        return envelope
    }
}

data class InvoiceEnvelopeSchema(val eventType: String, val schemaVersion: Int)
class UnsupportedInvoiceEnvelopeVersion(eventType: String, schemaVersion: Int) : IllegalArgumentException("unsupported_invoice_envelope:$eventType:$schemaVersion")
class InvalidInvoiceEnvelopeDigest(eventId: UUID) : IllegalArgumentException("invalid_invoice_envelope_digest:$eventId")
