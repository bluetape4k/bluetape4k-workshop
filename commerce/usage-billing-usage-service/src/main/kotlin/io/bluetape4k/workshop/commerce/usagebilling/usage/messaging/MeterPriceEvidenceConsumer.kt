package io.bluetape4k.workshop.commerce.usagebilling.usage.messaging

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.usagebilling.usage.application.PriceEvidenceService
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import tools.jackson.databind.JsonNode

/** Decodes Meter's published JSON without introducing a shared runtime message dependency. */
@Component
class MeterPriceEvidenceDecoder {
    fun decode(wirePayload: String): PriceEvidenceInboxEvent {
        val envelope = Jackson.defaultJsonMapper.readTree(wirePayload)
        val eventId = UUID.fromString(envelope.requiredText("eventId"))
        val eventType = envelope.requiredText("eventType")
        val schemaVersion = envelope.requiredInt("schemaVersion")
        if (eventType != PRICE_ACTIVATED || schemaVersion != SCHEMA_VERSION) {
            throw UnsupportedMeterPriceEvidenceEnvelope(eventType, schemaVersion)
        }
        val tenantId = envelope.requiredText("tenantId")
        val aggregateVersion = envelope.requiredLong("aggregateVersion")
        aggregateVersion.requirePositiveNumber("aggregateVersion")
        val payload = envelope.requiredText("payload")
        val payloadDigest = envelope.requiredText("payloadDigest")
        if (payloadDigest != digestOf(payload)) {
            throw InvalidMeterPriceEvidenceEnvelope(eventId)
        }
        val price = Jackson.defaultJsonMapper.readTree(payload)
        return PriceEvidenceInboxEvent(
            eventId = eventId,
            tenantId = tenantId,
            payloadDigest = payloadDigest,
            evidence = PriceEvidence(
                tenantId = tenantId,
                meterCode = price.requiredText("meterCode"),
                currency = price.requiredText("currency"),
                unitPrice = BigDecimal(price.requiredText("unitPrice")),
                effectiveAt = Instant.parse(envelope.requiredText("occurredAt")),
            ),
        )
    }

    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))

    private fun JsonNode.requiredText(name: String): String =
        requiredNode(name).asString().requireNotBlank("meterEnvelope.$name")

    private fun JsonNode.requiredInt(name: String): Int =
        requiredNode(name).asInt()

    private fun JsonNode.requiredLong(name: String): Long =
        requiredNode(name).asLong()

    private fun JsonNode.requiredNode(name: String): JsonNode =
        get(name) ?: throw InvalidMeterPriceEvidenceEnvelope(UUID(0, 0))

    private companion object {
        const val PRICE_ACTIVATED = "PriceActivated"
        const val SCHEMA_VERSION = 1
    }
}

class UnsupportedMeterPriceEvidenceEnvelope(
    eventType: String,
    schemaVersion: Int,
) : IllegalArgumentException("unsupported_meter_price_evidence_envelope:$eventType:$schemaVersion")

class InvalidMeterPriceEvidenceEnvelope(eventId: UUID) :
    IllegalArgumentException("invalid_meter_price_evidence_envelope:$eventId")

@Component
class KafkaUsagePriceEvidenceListener(
    private val decoder: MeterPriceEvidenceDecoder,
    private val priceEvidence: PriceEvidenceService,
) {
    @KafkaListener(
        topics = [METER_TOPIC],
        containerFactory = "usageKafkaListenerContainerFactory",
        groupId = "\${usage-billing.usage.kafka.consumer-group:usage-billing-usage-service}",
        autoStartup = "\${usage-billing.usage.kafka.listener-auto-startup:false}",
    )
    fun consume(wirePayload: String) {
        val event = decoder.decode(wirePayload)
        priceEvidence.record(event)
        log.debug { "usage.inbound.price_activated eventId=${event.eventId}" }
    }

    private companion object : KLogging() {
        const val METER_TOPIC = "meter.events.v1"
    }
}
