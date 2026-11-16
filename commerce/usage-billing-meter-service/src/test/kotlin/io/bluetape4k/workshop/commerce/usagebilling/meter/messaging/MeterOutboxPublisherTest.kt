package io.bluetape4k.workshop.commerce.usagebilling.meter.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

class MeterOutboxPublisherTest {

    private companion object: KLogging() {
        val NOW: Instant = Instant.parse("2026-07-22T00:00:00Z")
    }

    private val journal = InMemoryMeterOutboxJournal()
    private val transport = RecordingMeterEventTransport()
    private val publisher = MeterOutboxPublisher(journal, transport, Clock.fixed(NOW, ZoneOffset.UTC))

    @BeforeEach
    fun beforeEach() {
        journal.reset()
        transport.reset()
    }

    @Test
    fun `broker success marks the locally claimed outbox event published`() {
        val event = event()
        journal.events += event

        publisher.publishPending().published shouldBeEqualTo 1
        journal.statusOf(event.eventId) shouldBeEqualTo MeterOutboxStatus.PUBLISHED
        transport.keys shouldBeEqualTo listOf(event.partitionKey)
    }

    @Test
    fun `broker failure releases the local claim into retry wait`() {
        val event = event()
        journal.events += event
        transport.failure = MeterEventTransportFailure(IllegalStateException("broker_unavailable"))

        publisher.publishPending().retryWait shouldBeEqualTo 1
        journal.statusOf(event.eventId) shouldBeEqualTo MeterOutboxStatus.RETRY_WAIT
    }

    private fun event(): MeterOutboxLease =
        MeterOutboxLease(
            UUID.randomUUID(),
            "tenant-a|Meter|api-calls",
            "{\"event\":\"PriceActivated\"}",
            MeterOutboxStatus.PENDING,
        )

    private class InMemoryMeterOutboxJournal: MeterOutboxJournal {
        val events = mutableListOf<MeterOutboxLease>()

        override fun claim(owner: String, now: Instant, limit: Int): List<MeterOutboxLease> =
            events
                .filter { it.status == MeterOutboxStatus.PENDING }
                .take(limit)
                .map { event ->
                    event.copy(status = MeterOutboxStatus.CLAIMED, claimOwner = owner).also(::replace)
                }

        override fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean =
            events
                .firstOrNull { it.eventId == eventId && it.claimOwner == owner }
                ?.let { event ->
                    replace(event.copy(status = MeterOutboxStatus.PUBLISHED))
                    true
                } ?: false

        override fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean =
            events
                .firstOrNull { it.eventId == eventId && it.claimOwner == owner }
                ?.let { event ->
                    replace(event.copy(status = MeterOutboxStatus.RETRY_WAIT))
                    true
                } ?: false

        fun statusOf(eventId: UUID): MeterOutboxStatus =
            events.firstOrNull { it.eventId == eventId }.shouldNotBeNull().status

        private fun replace(event: MeterOutboxLease) {
            events[events.indexOfFirst { it.eventId == event.eventId }] = event
        }

        fun reset() {
            events.clear()
        }
    }

    private class RecordingMeterEventTransport: MeterEventTransport {
        val keys = mutableListOf<String>()
        var failure: MeterEventTransportFailure? = null

        override fun publish(partitionKey: String, payload: String) {
            failure?.let { throw it }
            keys += partitionKey
        }

        fun reset() {
            keys.clear()
            failure = null
        }
    }
}
