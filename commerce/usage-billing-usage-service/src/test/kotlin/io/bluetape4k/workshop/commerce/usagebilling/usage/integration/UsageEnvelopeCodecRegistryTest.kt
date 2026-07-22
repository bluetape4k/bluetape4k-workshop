package io.bluetape4k.workshop.commerce.usagebilling.usage.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class UsageEnvelopeCodecRegistryTest {
    @Test
    fun `usage event uses a tenant aggregate partition key`() {
        val envelope = usageEnvelope("UsageAccepted", 1)
        envelope.partitionKey() shouldBeEqualTo "tenant-a|Usage|source-42"
    }

    @Test
    fun `unsupported usage envelope is rejected`() {
        assertFailsWith<UnsupportedUsageEnvelopeVersion> {
            UsageEnvelopeCodecRegistry().validate(usageEnvelope("UsageAccepted", 99))
        }.message shouldBeEqualTo "unsupported_usage_envelope:UsageAccepted:99"
    }

    private fun usageEnvelope(eventType: String, schemaVersion: Int): UsageIntegrationEnvelope =
        UsageIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a02"),
            eventType = eventType,
            schemaVersion = schemaVersion,
            tenantId = "tenant-a",
            aggregateId = "source-42",
            aggregateVersion = 1,
            payload = "{\"quantity\":\"1\"}",
            occurredAt = Instant.parse("2026-07-22T00:00:00Z"),
            recordedAt = Instant.parse("2026-07-22T00:00:01Z"),
        )
}
