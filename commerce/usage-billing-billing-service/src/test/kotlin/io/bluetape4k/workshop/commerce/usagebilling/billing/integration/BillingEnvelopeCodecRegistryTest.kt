package io.bluetape4k.workshop.commerce.usagebilling.billing.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class BillingEnvelopeCodecRegistryTest {

    companion object: KLogging()

    @Test
    fun `wire payload retains the envelope identity and payload digest`() {
        val envelope = billingEnvelope("ChargeRated", 1)

        val wire = Jackson.defaultJsonMapper.readTree(envelope.wirePayload())

        wire.get("eventId")!!.asString() shouldBeEqualTo envelope.eventId.toString()
        wire.get("tenantId")!!.asString() shouldBeEqualTo "tenant-a"
        wire.get("payloadDigest")!!.asString() shouldBeEqualTo envelope.payloadDigest
    }

    @Test
    fun `billing event uses a tenant aggregate partition key`() {
        val envelope = billingEnvelope("ChargeRated", 1)
        envelope.partitionKey() shouldBeEqualTo "tenant-a|BillingPeriod|period-july"
    }

    @Test
    fun `unsupported billing envelope is rejected`() {
        assertFailsWith<UnsupportedBillingEnvelopeVersion> {
            BillingEnvelopeCodecRegistry().validate(
                billingEnvelope("ChargeRated", 99)
            )
        }.message shouldBeEqualTo "unsupported_billing_envelope:ChargeRated:99"
    }

    @Suppress("SameParameterValue")
    private fun billingEnvelope(
        eventType: String,
        schemaVersion: Int,
    ): BillingIntegrationEnvelope =
        BillingIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a03"),
            eventType = eventType,
            schemaVersion = schemaVersion,
            tenantId = "tenant-a",
            aggregateId = "period-july",
            aggregateVersion = 1,
            payload = "{\"amount\":\"10.00\"}",
            occurredAt = Instant.parse("2026-07-22T00:00:00Z"),
            recordedAt = Instant.parse("2026-07-22T00:00:01Z"),
        )
}
