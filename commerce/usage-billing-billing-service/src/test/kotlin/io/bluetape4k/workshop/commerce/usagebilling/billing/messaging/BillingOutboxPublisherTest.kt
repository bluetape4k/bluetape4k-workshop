package io.bluetape4k.workshop.commerce.usagebilling.billing.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class BillingOutboxPublisherTest {
    private val journal = InMemoryBillingOutboxJournal()
    private val transport = RecordingBillingEventTransport()
    private val publisher = BillingOutboxPublisher(journal, transport, Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `broker success marks the locally claimed Billing event published`() {
        val event = event()
        journal.events += event

        publisher.publishPending().published shouldBeEqualTo 1
        journal.statusOf(event.eventId) shouldBeEqualTo BillingOutboxStatus.PUBLISHED
        transport.keys shouldBeEqualTo listOf(event.partitionKey)
    }

    @Test
    fun `broker failure releases the local Billing claim into retry wait`() {
        val event = event()
        journal.events += event
        transport.failure = BillingEventTransportFailure(IllegalStateException("broker_unavailable"))

        publisher.publishPending().retryWait shouldBeEqualTo 1
        journal.statusOf(event.eventId) shouldBeEqualTo BillingOutboxStatus.RETRY_WAIT
    }

    private fun event(): BillingOutboxLease =
        BillingOutboxLease(UUID.randomUUID(), "tenant-a|BillingPeriod|period-1", "{\"event\":\"ChargeRated\"}")

    private class InMemoryBillingOutboxJournal : BillingOutboxJournal {
        val events = mutableListOf<BillingOutboxLease>()
        private val statusByEvent = mutableMapOf<UUID, BillingOutboxStatus>()
        private val ownerByEvent = mutableMapOf<UUID, String>()

        override fun claim(owner: String, now: Instant, limit: Int): List<BillingOutboxLease> =
            events.filter { statusOf(it.eventId) == BillingOutboxStatus.PENDING }
                .take(limit)
                .onEach { event ->
                    statusByEvent[event.eventId] = BillingOutboxStatus.CLAIMED
                    ownerByEvent[event.eventId] = owner
                }

        override fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean =
            ownerByEvent[eventId]?.takeIf { it == owner }?.let {
                statusByEvent[eventId] = BillingOutboxStatus.PUBLISHED
                true
            } ?: false

        override fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean =
            ownerByEvent[eventId]?.takeIf { it == owner }?.let {
                statusByEvent[eventId] = BillingOutboxStatus.RETRY_WAIT
                true
            } ?: false

        fun statusOf(eventId: UUID): BillingOutboxStatus =
            statusByEvent[eventId] ?: BillingOutboxStatus.PENDING
    }

    private class RecordingBillingEventTransport : BillingEventTransport {
        val keys = mutableListOf<String>()
        var failure: BillingEventTransportFailure? = null

        override fun publish(partitionKey: String, payload: String) {
            failure?.let { throw it }
            keys += partitionKey
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-22T00:00:00Z")
    }
}
