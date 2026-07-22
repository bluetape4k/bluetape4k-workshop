package io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.commerce.usagebilling.invoice.application.InvoiceInboxService
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import tools.jackson.databind.JsonNode

/** Maps Billing's wire contract into Invoice-owned inbox data without sharing producer types. */
@Component
class BillingChargeDecoder {
    fun decode(wirePayload: String): InvoiceInboxEvent {
        val envelope = Jackson.defaultJsonMapper.readTree(wirePayload)
        val eventType = envelope.requiredText("eventType")
        val schemaVersion = envelope.requiredInt("schemaVersion")
        if (eventType !in SUPPORTED_EVENT_TYPES || schemaVersion != SCHEMA_VERSION) {
            throw UnsupportedBillingChargeEnvelope(eventType, schemaVersion)
        }
        val payload = envelope.requiredText("payload")
        val digest = envelope.requiredText("payloadDigest")
        val eventId = UUID.fromString(envelope.requiredText("eventId"))
        if (digest != digestOf(payload)) throw InvalidBillingChargeEnvelope(eventId)
        val body = Jackson.defaultJsonMapper.readTree(payload)
        return InvoiceInboxEvent(
            eventId = eventId,
            eventType = eventType,
            correctionOf = body.optionalUuid("correctionOf"),
            amount = BigDecimal(body.requiredText("amount")),
            tenantId = envelope.requiredText("tenantId"),
            payloadDigest = digest,
        )
    }

    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))

    private fun JsonNode.requiredText(name: String): String =
        requireNotNull(get(name)) { "missing_invoice_envelope_field:$name" }.asString().also {
            require(it.isNotBlank()) { "blank_invoice_envelope_field:$name" }
        }

    private fun JsonNode.requiredInt(name: String): Int =
        requireNotNull(get(name)) { "missing_invoice_envelope_field:$name" }.asInt()

    private fun JsonNode.optionalUuid(name: String): UUID? =
        get(name)?.asString()?.takeIf(String::isNotBlank)?.let(UUID::fromString)

    private companion object {
        val SUPPORTED_EVENT_TYPES = setOf("ChargeRated", "AdjustmentPosted")
        const val SCHEMA_VERSION = 1
    }
}

class UnsupportedBillingChargeEnvelope(eventType: String, schemaVersion: Int) :
    IllegalArgumentException("unsupported_invoice_billing_envelope:$eventType:$schemaVersion")

class InvalidBillingChargeEnvelope(eventId: UUID) :
    IllegalArgumentException("invalid_invoice_billing_envelope:$eventId")

@Component
class KafkaBillingChargeListener(
    private val decoder: BillingChargeDecoder,
    private val inbox: InvoiceInboxService,
) {
    @KafkaListener(
        topics = [TOPIC],
        containerFactory = "invoiceKafkaListenerContainerFactory",
        groupId = "\${usage-billing.invoice.kafka.consumer-group:usage-billing-invoice-service}",
        autoStartup = "\${usage-billing.invoice.kafka.listener-auto-startup:false}",
    )
    fun consume(wirePayload: String) {
        inbox.handle(decoder.decode(wirePayload))
    }

    private companion object {
        const val TOPIC = "billing.events.v1"
    }
}
