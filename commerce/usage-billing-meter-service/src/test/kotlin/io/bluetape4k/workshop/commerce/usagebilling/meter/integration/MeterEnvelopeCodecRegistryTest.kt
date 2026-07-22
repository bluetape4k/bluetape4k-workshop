package io.bluetape4k.workshop.commerce.usagebilling.meter.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MeterEnvelopeCodecRegistryTest {
    @Test
    fun `meter event uses a tenant aggregate partition key`() {
        val envelope = meterEnvelope(eventType = "PriceActivated", schemaVersion = 1)

        envelope.partitionKey() shouldBeEqualTo "tenant-a|Meter|meter-api-calls"
    }

    @Test
    fun `unknown meter envelope schema is rejected before domain handling`() {
        val envelope = meterEnvelope(eventType = "PriceActivated", schemaVersion = 99)

        assertFailsWith<UnsupportedMeterEnvelopeVersion> {
            MeterEnvelopeCodecRegistry().validate(envelope)
        }.message shouldBeEqualTo "unsupported_meter_envelope:PriceActivated:99"
    }

    @Test
    fun `wire payload retains the envelope identity and payload digest`() {
        val envelope = meterEnvelope(eventType = "PriceActivated", schemaVersion = 1)

        val wire = Jackson.defaultJsonMapper.readTree(envelope.wirePayload())

        requireNotNull(wire.get("eventId")).asString() shouldBeEqualTo envelope.eventId.toString()
        requireNotNull(wire.get("tenantId")).asString() shouldBeEqualTo "tenant-a"
        requireNotNull(wire.get("payloadDigest")).asString() shouldBeEqualTo envelope.payloadDigest
    }

    private fun meterEnvelope(eventType: String, schemaVersion: Int): MeterIntegrationEnvelope =
        MeterIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01"),
            eventType = eventType,
            schemaVersion = schemaVersion,
            tenantId = "tenant-a",
            aggregateId = "meter-api-calls",
            aggregateVersion = 1,
            payload = "{\"unitPrice\":\"0.10\"}",
            occurredAt = Instant.parse("2026-07-22T00:00:00Z"),
            recordedAt = Instant.parse("2026-07-22T00:00:01Z"),
        )
}
