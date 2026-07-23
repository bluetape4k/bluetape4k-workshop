package io.bluetape4k.workshop.commerce.voucher.eventsourced.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.StreamReference
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventPage
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStorePort
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRead
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class BoundedRehydratorTest {
    private val stream =
        StreamKey(
            TenantId("tenant-a"),
            "campaign",
            UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc001"),
        )
    private val events = (1L..4L).map(::event)

    @Test
    fun `no snapshot replays the complete event stream`() {
        val result = rehydrator(null).rehydrate(request())

        result.state shouldBeEqualTo 4
        result.snapshotVersion shouldBeEqualTo 0L
    }

    @Test
    fun `valid snapshot replays only its tail`() {
        val result = rehydrator(snapshot(2, "2")).rehydrate(request())

        result.state shouldBeEqualTo 4
        result.snapshotVersion shouldBeEqualTo 2L
        result.replayedEvents shouldBeEqualTo 2
    }

    @Test
    fun `corrupt and retired-key snapshots fall back to full replay`() {
        val corrupt = BoundedRehydrator(FakeStore(events), { throw IllegalArgumentException("corrupt") })
        corrupt.rehydrate(request()).snapshotVersion shouldBeEqualTo 0L

        val retired = rehydrator(snapshot(2, "2", keyVersion = 2)).rehydrate(request(keyAvailable = { false }))
        retired.snapshotVersion shouldBeEqualTo 0L
    }

    @Test
    fun `snapshot newer than the committed head falls back to full replay`() {
        // Given
        val stale = snapshot(version = 5, state = "5")

        // When
        val result = rehydrator(stale).rehydrate(request())

        // Then
        result.state shouldBeEqualTo 4
        result.snapshotVersion shouldBeEqualTo 0L
    }

    @Test
    fun `replay cap rejects an unbounded foreground reconstruction`() {
        assertFailsWith<RehydrationLimitExceeded> {
            BoundedRehydrator(FakeStore(events), { null }, maxEvents = 3).rehydrate(request())
        }
    }

    private fun rehydrator(snapshot: EventSnapshot?) =
        BoundedRehydrator(
            eventStore = FakeStore(events),
            snapshotLoader = { snapshot },
        )

    private fun request(keyAvailable: (Int) -> Boolean = { true }) =
        RehydrationRequest(
            stream = stream,
            emptyState = { 0 },
            restoreSnapshot = String::toInt,
            apply = { state, page -> state + page.size },
            keyVersionAvailable = keyAvailable,
        )

    private fun snapshot(version: Long, state: String, keyVersion: Int = 1) =
        EventSnapshot(stream, SnapshotMetadata(version, 1, keyVersion), state, createdAt = NOW)

    private fun event(version: Long) =
        EventEnvelope(
            eventId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc0${version.toString().padStart(2, '0')}"),
            tenantId = stream.tenantId,
            stream = StreamReference(stream.streamType, stream.streamId, version),
            globalPosition = version,
            eventType = "campaign.created",
            schemaVersion = 1,
            occurredAt = NOW,
            recordedAt = NOW,
            correlationId = "correlation-$version",
            causationId = null,
            actorSurrogate = "actor",
            payload = EventPayload("{}"),
        )

    private class FakeStore(private val events: List<EventEnvelope>) : EventStorePort {
        override fun load(read: EventStoreRead): EventPage {
            val page = events.filter { it.stream.version > read.afterVersion }.take(read.limit)
            return EventPage(page, events.lastOrNull()?.stream?.version ?: 0)
        }

        override fun appendAll(appends: List<ExpectedAppend>): AppendResult = error("not used")
    }

    companion object {
        private val NOW = Instant.parse("2026-07-23T12:00:00Z")
    }
}
