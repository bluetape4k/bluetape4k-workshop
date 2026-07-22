package io.bluetape4k.workshop.commerce.usagebilling.query.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class QueryEnvelopeCodecRegistryTest {
    @Test
    fun `query event uses a tenant aggregate partition key`() {
        val envelope = queryEnvelope("ChargeRated", 1)
        envelope.partitionKey() shouldBeEqualTo "tenant-a|BillingPeriod|period-july"
    }

    @Test
    fun `unsupported query envelope is rejected`() {
        assertFailsWith<UnsupportedQueryEnvelopeVersion> {
            QueryEnvelopeCodecRegistry().validate(queryEnvelope("ChargeRated", 99))
        }.message shouldBeEqualTo "unsupported_query_envelope:ChargeRated:99"
    }

    private fun queryEnvelope(eventType: String, schemaVersion: Int): QueryIntegrationEnvelope =
        QueryIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a05"),
            eventType = eventType,
            schemaVersion = schemaVersion,
            tenantId = "tenant-a",
            aggregateType = "BillingPeriod",
            aggregateId = "period-july",
            aggregateVersion = 1,
            payload = "{\"amount\":\"10.00\"}",
            occurredAt = Instant.parse("2026-07-22T00:00:00Z"),
            recordedAt = Instant.parse("2026-07-22T00:00:01Z"),
        )
}
