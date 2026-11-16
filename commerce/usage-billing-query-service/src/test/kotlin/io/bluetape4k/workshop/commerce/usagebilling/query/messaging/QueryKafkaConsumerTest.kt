package io.bluetape4k.workshop.commerce.usagebilling.query.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryInboxService
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryQuarantineService
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryApplyResult
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.*

class QueryKafkaConsumerTest {

    private companion object: KLogging() {
        const val PAYLOAD = "{\"invoiceNumber\":\"INV-1\"}"
    }

    private val decoder = QueryInboundEventDecoder()

    private val inbox = mockk<QueryInboxService>(relaxed = true)
    private val quarantine = mockk<QueryQuarantineService>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(inbox, quarantine)
    }


    @Test
    fun `decodes supported upstream event into Query-owned projection input`() {
        val event = decoder.decode(envelope("InvoiceIssued"))

        log.debug { "event=$event" }
        event.tenantId shouldBeEqualTo "tenant-a"
        event.aggregateType shouldBeEqualTo "Invoice"
        event.aggregateId shouldBeEqualTo "invoice-1"
        event.payloadDigest shouldBeEqualTo digestOf(PAYLOAD)
    }

    @Test
    fun `decodes additive schema v2 through the compatibility boundary`() {
        val event = decoder.decode(envelope("InvoiceIssued", schemaVersion = 2))

        log.debug { "event=$event" }
        event.eventType shouldBeEqualTo "InvoiceIssued"
        event.aggregateVersion shouldBeEqualTo 1L
    }

    @Test
    fun `unknown mandatory schema retains metadata for durable quarantine`() {
        val failure = assertFailsWith<PermanentQueryInboundException> {
            decoder.decode(envelope("InvoiceIssued", schemaVersion = 99))
        }

        log.debug { "failure=$failure" }
        failure.quarantine.eventType shouldBeEqualTo "InvoiceIssued"
        failure.quarantine.reason shouldBeEqualTo "unsupported_schema:99"
    }

    @Test
    fun `rejects altered payload with enough metadata for durable quarantine`() {
        val failure = assertFailsWith<PermanentQueryInboundException> {
            decoder.decode(envelope("InvoiceIssued", payloadDigest = "0".repeat(64)))
        }

        log.debug { "failure=$failure" }
        failure.quarantine.eventType shouldBeEqualTo "InvoiceIssued"
        failure.quarantine.reason shouldBeEqualTo "invalid_payload_digest"
    }

    @Test
    fun `listener returns only after a valid event reaches the durable inbox service`() {
        every { inbox.apply(any()) } returns QueryApplyResult(applied = true)
        val listener = KafkaQueryIntegrationListener(decoder, inbox, quarantine)

        listener.consume(envelope("InvoiceIssued"))

        verify(exactly = 1) { inbox.apply(match { it.eventType == "InvoiceIssued" }) }
        verify(exactly = 0) { quarantine.record(any()) }
    }

    @Test
    fun `listener durably quarantines permanent decode failure instead of retrying forever`() {
        val listener = KafkaQueryIntegrationListener(decoder, inbox, quarantine)

        listener.consume(envelope("UnknownEvent"))

        verify(exactly = 0) { inbox.apply(any()) }
        verify(exactly = 1) {
            quarantine.record(match { it.eventType == "UnknownEvent" && it.reason == "unsupported_schema:1" })
        }
    }

    @Test
    fun `listener propagates transient inbox failure for Kafka redelivery`() {
        every { inbox.apply(any()) } throws IllegalStateException("database unavailable")
        val listener = KafkaQueryIntegrationListener(decoder, inbox, quarantine)

        assertFailsWith<IllegalStateException> {
            listener.consume(envelope("InvoiceIssued"))
        }
        verify(exactly = 0) { quarantine.record(any()) }
    }

    private fun envelope(
        eventType: String,
        payloadDigest: String = digestOf(PAYLOAD),
        schemaVersion: Int = 1,
    ): String =
        Jackson.defaultJsonMapper.writeValueAsString(
            linkedMapOf(
                "eventId" to "018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01",
                "eventType" to eventType,
                "schemaVersion" to schemaVersion,
                "tenantId" to "tenant-a",
                "aggregateType" to "Invoice",
                "aggregateId" to "invoice-1",
                "aggregateVersion" to 1,
                "payload" to PAYLOAD,
                "payloadDigest" to payloadDigest,
                "occurredAt" to "2026-07-22T00:00:00Z",
                "recordedAt" to "2026-07-22T00:00:01Z",
            ),
        )

    @Suppress("SameParameterValue")
    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()))
}
