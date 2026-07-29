package io.bluetape4k.workshop.commerce.usagebilling.billing.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

class BillingInboundEventDecoderTest {
    private val decoder = BillingInboundEventDecoder()

    @Test
    fun `decodes Meter price evidence without sharing Meter runtime types`() {
        val event = decoder.decode(envelope("PriceActivated", PRICE_PAYLOAD)) as BillingInboundEvent.PriceActivated

        event.event.payloadDigest shouldBeEqualTo digestOf(PRICE_PAYLOAD)
        event.event.evidence.tenantId shouldBeEqualTo "tenant-a"
        event.event.evidence.meterCode shouldBeEqualTo "api_calls"
        event.event.evidence.unitPrice.toPlainString() shouldBeEqualTo "0.10"
    }

    @Test
    fun `decodes Usage acceptance into Billing local rating input`() {
        val event = decoder.decode(
            envelope("UsageAccepted", USAGE_PAYLOAD, aggregateType = "Usage"),
        ) as BillingInboundEvent.UsageAccepted

        event.event.payloadDigest shouldBeEqualTo digestOf(USAGE_PAYLOAD)
        event.event.meterCode shouldBeEqualTo "api_calls"
        event.event.quantity.toPlainString() shouldBeEqualTo "2.5"
    }

    @Test
    fun `rejects an altered upstream payload before Billing persistence`() {
        assertFailsWith<InvalidBillingInboundEnvelope> {
            decoder.decode(envelope("UsageAccepted", USAGE_PAYLOAD, payloadDigest = "0".repeat(64)))
        }
    }

    @Test
    fun `rejects an unsupported upstream schema before Billing persistence`() {
        assertFailsWith<UnsupportedBillingInboundEnvelope> {
            decoder.decode(envelope("PriceActivated", PRICE_PAYLOAD, schemaVersion = 99))
        }
    }

    private fun envelope(
        eventType: String,
        payload: String,
        aggregateType: String = "Meter",
        schemaVersion: Int = 1,
        payloadDigest: String = digestOf(payload),
    ): String =
        Jackson.defaultJsonMapper.writeValueAsString(
            linkedMapOf(
                "eventId" to "018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01",
                "eventType" to eventType,
                "schemaVersion" to schemaVersion,
                "tenantId" to "tenant-a",
                "aggregateType" to aggregateType,
                "aggregateId" to "usage-1",
                "aggregateVersion" to 1,
                "payload" to payload,
                "payloadDigest" to payloadDigest,
                "occurredAt" to "2026-07-22T00:00:00Z",
                "recordedAt" to "2026-07-22T00:00:01Z",
            ),
        )

    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))

    private companion object {
        const val PRICE_PAYLOAD = "{\"meterCode\":\"api_calls\",\"currency\":\"USD\",\"unitPrice\":\"0.10\"}"
        const val USAGE_PAYLOAD = "{\"meterCode\":\"api_calls\",\"currency\":\"USD\",\"quantity\":\"2.5\"}"
    }
}
