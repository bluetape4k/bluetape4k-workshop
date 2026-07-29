package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingInboundEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.messaging.BillingInboundEventDecoder
import io.bluetape4k.workshop.commerce.usagebilling.billing.integration.BillingIntegrationEnvelope
import io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging.BillingChargeDecoder
import io.bluetape4k.workshop.commerce.usagebilling.meter.integration.MeterIntegrationEnvelope
import io.bluetape4k.workshop.commerce.usagebilling.query.messaging.QueryInboundEventDecoder
import io.bluetape4k.workshop.commerce.usagebilling.usage.integration.UsageIntegrationEnvelope
import io.bluetape4k.workshop.commerce.usagebilling.usage.messaging.MeterPriceEvidenceDecoder
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class UsageBillingWireContractTest {
    private val meterPriceEvidenceDecoder = MeterPriceEvidenceDecoder()
    private val billingDecoder = BillingInboundEventDecoder()
    private val invoiceDecoder = BillingChargeDecoder()
    private val queryDecoder = QueryInboundEventDecoder()

    @Test
    fun `Meter price wire contract is independently understood by Usage Billing and Query`() {
        val event = MeterIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01"),
            eventType = "PriceActivated",
            schemaVersion = 1,
            tenantId = "tenant-a",
            aggregateId = "api_calls",
            aggregateVersion = 1,
            payload = "{\"meterCode\":\"api_calls\",\"currency\":\"USD\",\"unitPrice\":\"0.10\"}",
            occurredAt = NOW,
            recordedAt = NOW,
        )

        meterPriceEvidenceDecoder.decode(event.wirePayload()).evidence.unitPrice.toPlainString() shouldBeEqualTo "0.10"
        (billingDecoder.decode(event.wirePayload()) as BillingInboundEvent.PriceActivated)
            .event.evidence.meterCode shouldBeEqualTo "api_calls"
        queryDecoder.decode(event.wirePayload()).aggregateType shouldBeEqualTo "Meter"
    }

    @Test
    fun `Usage acceptance wire contract preserves rating evidence for Billing and Query`() {
        val event = UsageIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a02"),
            eventType = "UsageAccepted",
            schemaVersion = 1,
            tenantId = "tenant-a",
            aggregateId = "usage-1",
            aggregateVersion = 1,
            payload = "{\"meterCode\":\"api_calls\",\"currency\":\"USD\",\"quantity\":\"2.5\"}",
            occurredAt = NOW,
            recordedAt = NOW,
        )

        val billingEvent = billingDecoder.decode(event.wirePayload()) as BillingInboundEvent.UsageAccepted

        billingEvent.event.quantity.toPlainString() shouldBeEqualTo "2.5"
        billingEvent.event.payloadDigest shouldBeEqualTo event.payloadDigest
        queryDecoder.decode(event.wirePayload()).aggregateType shouldBeEqualTo "Usage"
    }

    @Test
    fun `Billing charge wire contract is independently understood by Invoice and Query`() {
        val event = BillingIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a03"),
            eventType = "ChargeRated",
            schemaVersion = 1,
            tenantId = "tenant-a",
            aggregateId = "period-2026-07",
            aggregateVersion = 1,
            payload = "{\"amount\":\"0.25\",\"currency\":\"USD\"}",
            occurredAt = NOW,
            recordedAt = NOW,
        )

        invoiceDecoder.decode(event.wirePayload()).amount.toPlainString() shouldBeEqualTo "0.25"
        queryDecoder.decode(event.wirePayload()).aggregateType shouldBeEqualTo "BillingPeriod"
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-23T00:00:00Z")
    }
}
