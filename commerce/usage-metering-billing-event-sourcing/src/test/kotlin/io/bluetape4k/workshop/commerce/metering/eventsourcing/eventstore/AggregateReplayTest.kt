package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterRegistered
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PriceActivated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class AggregateReplayTest {

    companion object: KLogging()

    private val stream = StreamKey("tenant-a", "Meter", "api_calls")

    private val registry = EventCodecRegistry()
        .register("meter.registered", 1) {
            MeterRegistered(
                meterCode = "api_calls",
                unit = "request",
                currency = "USD"
            )
        }
        .register("price.activated", 1) {
            PriceActivated(
                currency = "USD",
                unitPrice = BigDecimal("0.10"),
                effectiveFrom = Instant.parse("2026-06-01T00:00:00Z")
            )
        }
    private val policy = ReplayPolicy(registry, 1)

    @Test
    fun `valid snapshot replays only its tail to the same state`() {
        val events = events()
        val afterFirst = MeterReducer.evolve(MeterState.Empty, MeterRegistered("api_calls", "request", "USD"))
        val snapshot = AggregateSnapshotSeed(afterFirst, 1, events.first().eventHash, 1)

        val full = AggregateReplayer.replay(events, MeterState.Empty, MeterReducer, null, policy)
        val restored = AggregateReplayer.replay(events, MeterState.Empty, MeterReducer, snapshot, policy)

        log.debug { "restored: $restored" }
        restored shouldBeEqualTo full
        restored.streamVersion shouldBeEqualTo 2L
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

        log.debug { "restored: $restored" }
        restored.streamVersion shouldBeEqualTo 2L
        restored.state.shouldBeInstanceOf<MeterState.Active>().meterCode shouldBeEqualTo "api_calls"

        log.debug { "telemetry: $telemetry" }
        telemetry.snapshotFallbacks shouldBeEqualTo listOf("invalid")
        telemetry.replays shouldBeEqualTo listOf("success" to 2)
    }

    @Test
    fun `tampered persisted payload fails before decoding`() {
        val events = events().toMutableList()
        events[1] = events[1].copy(payload = """{"unitPrice":999}""")

        assertFailsWith<EventHashMismatchException> {
            AggregateReplayer.replay(
                events = events,
                initialState = MeterState.Empty,
                reducer = MeterReducer,
                snapshot = null,
                policy = policy
            )
        }
    }

    private fun events(): List<PersistedEvent> {
        val first = persisted(1, "meter.registered", """{"code":"api_calls"}""", null)
        val second = persisted(2, "price.activated", """{"unitPrice":0.10}""", first.eventHash)
        return listOf(first, second)
    }

    private fun persisted(version: Long, type: String, payload: String, previousHash: String?): PersistedEvent {
        val material = EventHashMaterial(
            stream = stream,
            streamVersion = version,
            eventType = type,
            schemaVersion = 1,
            payload = payload,
            metadata = "{}",
            previousHash = previousHash
        )
        return PersistedEvent(
            eventId = UUID.randomUUID(),
            stream = stream,
            streamVersion = version,
            globalPosition = version,
            eventType = type,
            schemaVersion = 1,
            payload = payload,
            metadata = "{}",
            previousHash = previousHash,
            eventHash = CanonicalEventHash.sha256(material),
            occurredAt = Instant.EPOCH,
            recordedAt = Instant.EPOCH,
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
