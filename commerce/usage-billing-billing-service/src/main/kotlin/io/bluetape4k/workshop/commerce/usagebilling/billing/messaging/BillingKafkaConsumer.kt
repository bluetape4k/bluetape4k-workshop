package io.bluetape4k.workshop.commerce.usagebilling.billing.messaging

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.usagebilling.billing.application.BillingInboxService
import io.bluetape4k.workshop.commerce.usagebilling.billing.application.BillingPricingEvidenceService
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidenceEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.io.Serializable
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import tools.jackson.databind.JsonNode

sealed interface BillingInboundEvent : Serializable {
    data class PriceActivated(val event: BillingPriceEvidenceEvent) : BillingInboundEvent {
        private companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    data class UsageAccepted(val event: BillingInboxEvent) : BillingInboundEvent {
        private companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}

/** Parses producer JSON locally so Billing owns its own compatibility decision. */
@Component
class BillingInboundEventDecoder {
    fun decode(wirePayload: String): BillingInboundEvent {
        val envelope = Jackson.defaultJsonMapper.readTree(wirePayload)
        val eventType = envelope.requiredText("eventType")
        val schemaVersion = envelope.requiredInt("schemaVersion")
        requireSupportedSchema(eventType, schemaVersion)
        val eventId = UUID.fromString(envelope.requiredText("eventId"))
        val tenantId = envelope.requiredText("tenantId")
        val aggregateType = envelope.requiredText("aggregateType")
        val aggregateId = envelope.requiredText("aggregateId")
        val aggregateVersion = envelope.requiredLong("aggregateVersion")
        val payload = envelope.requiredText("payload")
        val payloadDigest = envelope.requiredText("payloadDigest")
        verifyPayloadDigest(eventId, payload, payloadDigest)
        val body = Jackson.defaultJsonMapper.readTree(payload)
        return when (eventType) {
            PRICE_ACTIVATED -> BillingInboundEvent.PriceActivated(
                BillingPriceEvidenceEvent(
                    eventId = eventId,
                    payloadDigest = payloadDigest,
                    evidence = BillingPriceEvidence(
                        tenantId = tenantId,
                        meterCode = body.requiredText("meterCode"),
                        currency = body.requiredText("currency"),
                        unitPrice = BigDecimal(body.requiredText("unitPrice")),
                        effectiveAt = Instant.parse(envelope.requiredText("occurredAt")),
                    ),
                ),
            )
            USAGE_ACCEPTED -> BillingInboundEvent.UsageAccepted(
                BillingInboxEvent(
                    eventId = eventId,
                    tenantId = tenantId,
                    aggregateType = aggregateType,
                    aggregateId = aggregateId,
                    aggregateVersion = aggregateVersion,
                    payloadDigest = payloadDigest,
                    meterCode = body.requiredText("meterCode"),
                    currency = body.requiredText("currency"),
                    quantity = BigDecimal(body.requiredText("quantity")),
                ),
            )
            else -> throw UnsupportedBillingInboundEnvelope(eventType, schemaVersion)
        }
    }

    private fun requireSupportedSchema(eventType: String, schemaVersion: Int) {
        if (schemaVersion != SCHEMA_VERSION) throw UnsupportedBillingInboundEnvelope(eventType, schemaVersion)
    }

    private fun verifyPayloadDigest(eventId: UUID, payload: String, payloadDigest: String) {
        if (payloadDigest != digestOf(payload)) throw InvalidBillingInboundEnvelope(eventId)
    }

    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))

    private fun JsonNode.requiredText(name: String): String =
        requiredNode(name).asString().requireNotBlank("billingEnvelope.$name")

    private fun JsonNode.requiredInt(name: String): Int =
        requiredNode(name).asInt()

    private fun JsonNode.requiredLong(name: String): Long =
        requiredNode(name).asLong()

    private fun JsonNode.requiredNode(name: String): JsonNode =
        get(name) ?: throw InvalidBillingInboundEnvelope(UUID(0, 0))

    private companion object {
        const val PRICE_ACTIVATED = "PriceActivated"
        const val USAGE_ACCEPTED = "UsageAccepted"
        const val SCHEMA_VERSION = 1
    }
}

class UnsupportedBillingInboundEnvelope(eventType: String, schemaVersion: Int) :
    IllegalArgumentException("unsupported_billing_inbound_envelope:$eventType:$schemaVersion")

class InvalidBillingInboundEnvelope(eventId: UUID) :
    IllegalArgumentException("invalid_billing_inbound_envelope:$eventId")

@Component
class KafkaBillingIntegrationListener(
    private val decoder: BillingInboundEventDecoder,
    private val pricingEvidence: BillingPricingEvidenceService,
    private val inbox: BillingInboxService,
) {
    @KafkaListener(
        topics = [METER_TOPIC, USAGE_TOPIC],
        containerFactory = "billingKafkaListenerContainerFactory",
        groupId = "\${usage-billing.billing.kafka.consumer-group:usage-billing-billing-service}",
        autoStartup = "\${usage-billing.billing.kafka.listener-auto-startup:false}",
    )
    fun consume(wirePayload: String) {
        when (val event = decoder.decode(wirePayload)) {
            is BillingInboundEvent.PriceActivated -> {
                pricingEvidence.record(event.event)
                log.debug { "billing.inbound.price_activated eventId=${event.event.eventId}" }
            }
            is BillingInboundEvent.UsageAccepted -> {
                inbox.handle(event.event)
                log.debug { "billing.inbound.usage_accepted eventId=${event.event.eventId}" }
            }
        }
    }

    private companion object : KLogging() {
        const val METER_TOPIC = "meter.events.v1"
        const val USAGE_TOPIC = "usage.events.v1"
    }
}
