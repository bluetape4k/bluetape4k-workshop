package io.bluetape4k.workshop.commerce.usagebilling.usage.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

class MeterPriceEvidenceDecoderTest {
    private val decoder = MeterPriceEvidenceDecoder()

    @Test
    fun `decodes a complete Meter price envelope into local evidence`() {
        val evidence = decoder.decode(wirePayload())

        evidence.tenantId shouldBeEqualTo "tenant-a"
        evidence.evidence.meterCode shouldBeEqualTo "api_calls"
        evidence.evidence.unitPrice.toPlainString() shouldBeEqualTo "0.10"
    }

    @Test
    fun `rejects unknown Meter message versions before local persistence`() {
        assertFailsWith<UnsupportedMeterPriceEvidenceEnvelope> {
            decoder.decode(wirePayload(schemaVersion = 99))
        }.message shouldBeEqualTo "unsupported_meter_price_evidence_envelope:PriceActivated:99"
    }

    @Test
    fun `rejects a Meter message whose inner payload digest was altered`() {
        assertFailsWith<InvalidMeterPriceEvidenceEnvelope> {
            decoder.decode(wirePayload(payloadDigest = "0".repeat(64)))
        }.message shouldBeEqualTo "invalid_meter_price_evidence_envelope:018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01"
    }

    private fun wirePayload(
        schemaVersion: Int = 1,
        payloadDigest: String = digestOf(PAYLOAD),
    ): String =
        Jackson.defaultJsonMapper.writeValueAsString(
            linkedMapOf(
                "eventId" to "018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01",
                "eventType" to "PriceActivated",
                "schemaVersion" to schemaVersion,
                "tenantId" to "tenant-a",
                "aggregateType" to "Meter",
                "aggregateId" to "api_calls",
                "aggregateVersion" to 1,
                "payload" to PAYLOAD,
                "payloadDigest" to payloadDigest,
                "occurredAt" to "2026-07-22T00:00:00Z",
                "recordedAt" to "2026-07-22T00:00:01Z",
            ),
        )

    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))

    private companion object {
        const val PAYLOAD = "{\"meterCode\":\"api_calls\",\"currency\":\"USD\",\"unitPrice\":\"0.10\"}"
    }
}
