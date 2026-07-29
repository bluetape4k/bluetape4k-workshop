package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterRegistered
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PriceActivated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class AggregateReplayTest {
    private val stream = StreamKey("tenant-a", "Meter", "api_calls")
    private val registry = EventCodecRegistry()
        .register("meter.registered", 1) { MeterRegistered("api_calls", "request", "USD") }
        .register("price.activated", 1) {
            PriceActivated("USD", BigDecimal("0.10"), Instant.parse("2026-06-01T00:00:00Z"))
        }
    private val policy = ReplayPolicy(registry, 1)

    @Test
    fun `valid snapshot replays only its tail to the same state`() {
        val events = events()
        val afterFirst = MeterReducer.evolve(MeterState.Empty, MeterRegistered("api_calls", "request", "USD"))
        val snapshot = AggregateSnapshotSeed(afterFirst, 1, events.first().eventHash, 1)

        val full = AggregateReplayer.replay(events, MeterState.Empty, MeterReducer, null, policy)
        val restored = AggregateReplayer.replay(events, MeterState.Empty, MeterReducer, snapshot, policy)

        assertEquals(full, restored)
        assertEquals(2L, restored.streamVersion)
    }

    @Test
    fun `invalid snapshot falls back to genesis replay`() {
        val events = events()
        val invalid = AggregateSnapshotSeed<MeterState>(MeterState.Empty, 1, "wrong-hash", 1)
        val telemetry = RecordingReplayTelemetry()

        val restored = AggregateReplayer.replay(
            AggregateReplayRequest(events, MeterState.Empty, MeterReducer, invalid, policy),
            telemetry,
        )

        assertEquals(2L, restored.streamVersion)
        assertEquals("api_calls", (restored.state as MeterState.Active).meterCode)
        assertEquals(listOf("invalid"), telemetry.snapshotFallbacks)
        assertEquals(listOf("success" to 2), telemetry.replays)
    }

    @Test
    fun `tampered persisted payload fails before decoding`() {
        val events = events().toMutableList()
        events[1] = events[1].copy(payload = """{"unitPrice":999}""")

        assertThrows(EventHashMismatchException::class.java) {
            AggregateReplayer.replay(events, MeterState.Empty, MeterReducer, null, policy)
        }
    }

    private fun events(): List<PersistedEvent> {
        val first = persisted(1, "meter.registered", """{"code":"api_calls"}""", null)
        val second = persisted(2, "price.activated", """{"unitPrice":0.10}""", first.eventHash)
        return listOf(first, second)
    }

    private fun persisted(version: Long, type: String, payload: String, previousHash: String?): PersistedEvent {
        val material = EventHashMaterial(stream, version, type, 1, payload, "{}", previousHash)
        return PersistedEvent(
            UUID.randomUUID(), stream, version, version, type, 1, payload, "{}", previousHash,
            CanonicalEventHash.sha256(material), Instant.EPOCH, Instant.EPOCH,
        )
    }

    private class RecordingReplayTelemetry : ReplayTelemetry {
        val replays = mutableListOf<Pair<String, Int>>()
        val snapshotFallbacks = mutableListOf<String>()

        override fun recordReplay(outcome: String, eventCount: Int, duration: Duration) {
            check(!duration.isNegative)
            replays += outcome to eventCount
        }

        override fun recordSnapshotFallback(reason: String) {
            snapshotFallbacks += reason
        }
    }
}
