package io.bluetape4k.workshop.commerce.usagebilling.query.messaging

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryInboxService
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryQuarantineService
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryQuarantineEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import tools.jackson.databind.JsonNode

@Component
class QueryInboundEventDecoder {
    fun decode(wirePayload: String): QueryInboxEvent {
        val envelope = Jackson.defaultJsonMapper.readTree(wirePayload)
        val eventId = envelope.eventIdOrFallback()
        val eventType = envelope.optionalText("eventType") ?: "unknown"
        val tenantId = envelope.optionalText("tenantId") ?: "unknown"
        val schemaVersion = envelope.optionalInt("schemaVersion") ?: 0
        if (eventType !in SUPPORTED_EVENT_TYPES) {
            fail(eventId, tenantId, eventType, "unsupported_schema:$schemaVersion")
        }
        if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            fail(eventId, tenantId, eventType, "unsupported_schema:$schemaVersion")
        }
        val payload = envelope.requiredText("payload", eventId, tenantId, eventType)
        val payloadDigest = envelope.requiredText("payloadDigest", eventId, tenantId, eventType)
        if (payloadDigest != digestOf(payload)) {
            fail(eventId, tenantId, eventType, "invalid_payload_digest")
        }
        return QueryInboxEvent(
            eventId = eventId,
            tenantId = tenantId,
            eventType = eventType,
            aggregateType = envelope.requiredText("aggregateType", eventId, tenantId, eventType),
            aggregateId = envelope.requiredText("aggregateId", eventId, tenantId, eventType),
            aggregateVersion = envelope.requiredLong("aggregateVersion", eventId, tenantId, eventType),
            payload = payload,
            payloadDigest = payloadDigest,
            receivedAt = Instant.now(),
        )
    }

    private fun JsonNode.eventIdOrFallback(): UUID =
        optionalText("eventId")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UNKNOWN_EVENT_ID

    private fun JsonNode.requiredText(eventId: UUID, tenantId: String, eventType: String, name: String): String =
        optionalText(name)?.takeIf(String::isNotBlank)
            ?: fail(eventId, tenantId, eventType, "missing_$name")

    private fun JsonNode.requiredText(name: String, eventId: UUID, tenantId: String, eventType: String): String =
        requiredText(eventId, tenantId, eventType, name)

    private fun JsonNode.requiredLong(name: String, eventId: UUID, tenantId: String, eventType: String): Long =
        optionalLong(name)?.takeIf { it > 0 } ?: fail(eventId, tenantId, eventType, "invalid_$name")

    private fun JsonNode.optionalText(name: String): String? = get(name)?.asString()

    private fun JsonNode.optionalInt(name: String): Int? = get(name)?.asInt()

    private fun JsonNode.optionalLong(name: String): Long? = get(name)?.asLong()

    private fun fail(eventId: UUID, tenantId: String, eventType: String, reason: String): Nothing =
        throw PermanentQueryInboundException(QueryQuarantineEvent(eventId, tenantId, eventType, reason, Instant.now()))

    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))

    private companion object {
        val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2)
        val UNKNOWN_EVENT_ID: UUID = UUID(0, 0)
        val SUPPORTED_EVENT_TYPES = setOf(
            "PriceActivated", "UsageAccepted", "UsageCorrected", "ChargeRated", "AdjustmentPosted",
            "InvoiceIssued", "InvoiceCorrectionIssued",
        )
    }
}

class PermanentQueryInboundException(val quarantine: QueryQuarantineEvent) :
    IllegalArgumentException("permanent_query_inbound:${quarantine.reason}")

@Component
class KafkaQueryIntegrationListener(
    private val decoder: QueryInboundEventDecoder,
    private val inbox: QueryInboxService,
    private val quarantine: QueryQuarantineService,
) {
    @KafkaListener(
        topics = ["meter.events.v1", "usage.events.v1", "billing.events.v1", "invoice.events.v1"],
        containerFactory = "queryKafkaListenerContainerFactory",
        groupId = "\${usage-billing.query.kafka.consumer-group:usage-billing-query-service}",
        autoStartup = "\${usage-billing.query.kafka.listener-auto-startup:false}",
    )
    fun consume(wirePayload: String) {
        try {
            inbox.apply(decoder.decode(wirePayload))
        } catch (failure: PermanentQueryInboundException) {
            quarantine.record(failure.quarantine)
        }
    }
}
