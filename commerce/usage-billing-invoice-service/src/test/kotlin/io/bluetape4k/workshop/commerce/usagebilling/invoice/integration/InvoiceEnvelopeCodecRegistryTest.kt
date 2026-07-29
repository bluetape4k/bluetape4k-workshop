package io.bluetape4k.workshop.commerce.usagebilling.invoice.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InvoiceEnvelopeCodecRegistryTest {
    @Test
    fun `wire payload retains the envelope identity and payload digest`() {
        val envelope = invoiceEnvelope("InvoiceIssued", 1)

        val wire = Jackson.defaultJsonMapper.readTree(envelope.wirePayload())

        requireNotNull(wire.get("eventId")).asString() shouldBeEqualTo envelope.eventId.toString()
        requireNotNull(wire.get("tenantId")).asString() shouldBeEqualTo "tenant-a"
        requireNotNull(wire.get("payloadDigest")).asString() shouldBeEqualTo envelope.payloadDigest
    }

    @Test
    fun `invoice event uses a tenant aggregate partition key`() {
        val envelope = invoiceEnvelope("InvoiceIssued", 1)
        envelope.partitionKey() shouldBeEqualTo "tenant-a|Invoice|invoice-7"
    }

    @Test
    fun `unsupported invoice envelope is rejected`() {
        assertFailsWith<UnsupportedInvoiceEnvelopeVersion> {
            InvoiceEnvelopeCodecRegistry().validate(invoiceEnvelope("InvoiceIssued", 99))
        }.message shouldBeEqualTo "unsupported_invoice_envelope:InvoiceIssued:99"
    }

    private fun invoiceEnvelope(eventType: String, schemaVersion: Int): InvoiceIntegrationEnvelope =
        InvoiceIntegrationEnvelope.create(
            eventId = UUID.fromString("018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a04"),
            eventType = eventType,
            schemaVersion = schemaVersion,
            tenantId = "tenant-a",
            aggregateId = "invoice-7",
            aggregateVersion = 1,
            payload = "{\"invoiceNumber\":\"INV-7\"}",
            occurredAt = Instant.parse("2026-07-22T00:00:00Z"),
            recordedAt = Instant.parse("2026-07-22T00:00:01Z"),
        )
}
