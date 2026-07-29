package io.bluetape4k.workshop.commerce.usagebilling.usage.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class UsageOutboxPublisherTest {
    private val journal = InMemoryUsageOutboxJournal()
    private val transport = RecordingUsageEventTransport()
    private val publisher = UsageOutboxPublisher(journal, transport, Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `broker success marks the locally claimed Usage outbox event published`() {
        val event = event()
        journal.events += event

        publisher.publishPending().published shouldBeEqualTo 1
        journal.statusOf(event.eventId) shouldBeEqualTo UsageOutboxStatus.PUBLISHED
        transport.keys shouldBeEqualTo listOf(event.partitionKey)
    }

    @Test
    fun `broker failure releases the local Usage claim into retry wait`() {
        val event = event()
        journal.events += event
        transport.failure = UsageEventTransportFailure(IllegalStateException("broker_unavailable"))

        publisher.publishPending().retryWait shouldBeEqualTo 1
        journal.statusOf(event.eventId) shouldBeEqualTo UsageOutboxStatus.RETRY_WAIT
    }

    private fun event(): UsageOutboxLease =
        UsageOutboxLease(UUID.randomUUID(), "tenant-a|Usage|source-1", "{\"event\":\"UsageAccepted\"}")

    private class InMemoryUsageOutboxJournal : UsageOutboxJournal {
        val events = mutableListOf<UsageOutboxLease>()
        private val statusByEvent = mutableMapOf<UUID, UsageOutboxStatus>()
        private val ownerByEvent = mutableMapOf<UUID, String>()

        override fun claim(owner: String, now: Instant, limit: Int): List<UsageOutboxLease> =
            events.filter { statusOf(it.eventId) == UsageOutboxStatus.PENDING }
                .take(limit)
                .onEach { event ->
                    statusByEvent[event.eventId] = UsageOutboxStatus.CLAIMED
                    ownerByEvent[event.eventId] = owner
                }

        override fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean =
            ownerByEvent[eventId]?.takeIf { it == owner }?.let {
                statusByEvent[eventId] = UsageOutboxStatus.PUBLISHED
                true
            } ?: false

        override fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean =
            ownerByEvent[eventId]?.takeIf { it == owner }?.let {
                statusByEvent[eventId] = UsageOutboxStatus.RETRY_WAIT
                true
            } ?: false

        fun statusOf(eventId: UUID): UsageOutboxStatus =
            statusByEvent[eventId] ?: UsageOutboxStatus.PENDING
    }

    private class RecordingUsageEventTransport : UsageEventTransport {
        val keys = mutableListOf<String>()
        var failure: UsageEventTransportFailure? = null

        override fun publish(partitionKey: String, payload: String) {
            failure?.let { throw it }
            keys += partitionKey
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-22T00:00:00Z")
    }
}
