package io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

class BillingChargeDecoderTest {
    private val decoder = BillingChargeDecoder()

    @Test
    fun `decodes Billing ChargeRated into an Invoice-owned inbox event`() {
        val event = decoder.decode(envelope("ChargeRated", PAYLOAD))

        event.tenantId shouldBeEqualTo "tenant-a"
        event.amount.toPlainString() shouldBeEqualTo "0.20"
        event.payloadDigest shouldBeEqualTo digestOf(PAYLOAD)
    }

    @Test
    fun `rejects altered Billing payload before Invoice persistence`() {
        assertFailsWith<InvalidBillingChargeEnvelope> {
            decoder.decode(envelope("ChargeRated", PAYLOAD, "0".repeat(64)))
        }
    }

    @Test
    fun `rejects unsupported Billing schema before Invoice persistence`() {
        assertFailsWith<UnsupportedBillingChargeEnvelope> {
            decoder.decode(envelope("ChargeRated", PAYLOAD, schemaVersion = 2))
        }
    }

    private fun envelope(
        eventType: String,
        payload: String,
        digest: String = digestOf(payload),
        schemaVersion: Int = 1,
    ): String =
        Jackson.defaultJsonMapper.writeValueAsString(
            linkedMapOf(
                "eventId" to "018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01",
                "eventType" to eventType,
                "schemaVersion" to schemaVersion,
                "tenantId" to "tenant-a",
                "aggregateType" to "Billing",
                "aggregateId" to "usage-1",
                "aggregateVersion" to 1,
                "payload" to payload,
                "payloadDigest" to digest,
                "occurredAt" to "2026-07-22T00:00:00Z",
                "recordedAt" to "2026-07-22T00:00:01Z",
            ),
        )

    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))

    private companion object {
        const val PAYLOAD = "{\"sourceEventId\":\"018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a02\"," +
            "\"currency\":\"USD\",\"amount\":\"0.20\"}"
    }
}
