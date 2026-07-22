package io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class InvoiceOutboxPublisherTest {
    private val journal = InMemoryInvoiceOutboxJournal()
    private val transport = RecordingInvoiceEventTransport()
    private val publisher = InvoiceOutboxPublisher(journal, transport, Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `broker success marks the locally claimed Invoice event published`() {
        val event = event()
        journal.events += event

        publisher.publishPending().published shouldBeEqualTo 1
        journal.statusOf(event.eventId) shouldBeEqualTo InvoiceOutboxStatus.PUBLISHED
        transport.keys shouldBeEqualTo listOf(event.partitionKey)
    }

    @Test
    fun `broker failure releases the local Invoice claim into retry wait`() {
        val event = event()
        journal.events += event
        transport.failure = InvoiceEventTransportFailure(IllegalStateException("broker_unavailable"))

        publisher.publishPending().retryWait shouldBeEqualTo 1
        journal.statusOf(event.eventId) shouldBeEqualTo InvoiceOutboxStatus.RETRY_WAIT
    }

    private fun event(): InvoiceOutboxLease =
        InvoiceOutboxLease(UUID.randomUUID(), "tenant-a|Invoice|invoice-1", "{\"event\":\"InvoiceIssued\"}")

    private class InMemoryInvoiceOutboxJournal : InvoiceOutboxJournal {
        val events = mutableListOf<InvoiceOutboxLease>()
        private val statusByEvent = mutableMapOf<UUID, InvoiceOutboxStatus>()
        private val ownerByEvent = mutableMapOf<UUID, String>()

        override fun claim(owner: String, now: Instant, limit: Int): List<InvoiceOutboxLease> =
            events.filter { statusOf(it.eventId) == InvoiceOutboxStatus.PENDING }
                .take(limit)
                .onEach { event ->
                    statusByEvent[event.eventId] = InvoiceOutboxStatus.CLAIMED
                    ownerByEvent[event.eventId] = owner
                }

        override fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean =
            ownerByEvent[eventId]?.takeIf { it == owner }?.let {
                statusByEvent[eventId] = InvoiceOutboxStatus.PUBLISHED
                true
            } ?: false

        override fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean =
            ownerByEvent[eventId]?.takeIf { it == owner }?.let {
                statusByEvent[eventId] = InvoiceOutboxStatus.RETRY_WAIT
                true
            } ?: false

        fun statusOf(eventId: UUID): InvoiceOutboxStatus =
            statusByEvent[eventId] ?: InvoiceOutboxStatus.PENDING
    }

    private class RecordingInvoiceEventTransport : InvoiceEventTransport {
        val keys = mutableListOf<String>()
        var failure: InvoiceEventTransportFailure? = null

        override fun publish(partitionKey: String, payload: String) {
            failure?.let { throw it }
            keys += partitionKey
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-22T00:00:00Z")
    }
}
