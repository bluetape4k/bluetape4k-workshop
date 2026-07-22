package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AggregateReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.ReplayedAggregate
import java.time.Duration

interface ReplayTelemetry {
    fun recordReplay(outcome: String, eventCount: Int, duration: Duration)
    fun recordSnapshotFallback(reason: String)
}

data class AggregateSnapshotSeed<S>(
    val state: S,
    val streamVersion: Long,
    val lastEventHash: String,
    val reducerVersion: Int,
)

data class ReplayPolicy(
    val registry: EventCodecRegistry,
    val reducerVersion: Int,
) {
    init {
        require(reducerVersion > 0) { "reducer_version_invalid" }
    }
}

data class AggregateReplayRequest<S>(
    val events: List<PersistedEvent>,
    val initialState: S,
    val reducer: AggregateReducer<S>,
    val snapshot: AggregateSnapshotSeed<S>?,
    val policy: ReplayPolicy,
)

class EventHashMismatchException(eventId: java.util.UUID) : IllegalStateException("event_hash_mismatch:$eventId")

object AggregateReplayer {
    fun <S> replay(
        events: List<PersistedEvent>,
        initialState: S,
        reducer: AggregateReducer<S>,
        snapshot: AggregateSnapshotSeed<S>?,
        policy: ReplayPolicy,
    ): ReplayedAggregate<S> = replay(AggregateReplayRequest(events, initialState, reducer, snapshot, policy))

    fun <S> replay(
        request: AggregateReplayRequest<S>,
        telemetry: ReplayTelemetry? = null,
    ): ReplayedAggregate<S> {
        val startedAt = System.nanoTime()
        val validSnapshot = request.snapshot?.takeIf { candidate ->
            candidate.reducerVersion == request.policy.reducerVersion &&
                request.events
                    .firstOrNull { it.streamVersion == candidate.streamVersion }
                    ?.eventHash == candidate.lastEventHash
        }
        if (request.snapshot != null && validSnapshot == null) telemetry?.recordSnapshotFallback("invalid")
        var state = validSnapshot?.state ?: request.initialState
        var previousHash = validSnapshot?.lastEventHash
        var version = validSnapshot?.streamVersion ?: 0L
        val replayEvents = request.events.filter { it.streamVersion > version }
        return runCatching {
            replayEvents.forEach { persisted ->
                verifyHash(persisted, previousHash)
                val decoded = request.policy.registry.decode(
                    persisted.eventType,
                    persisted.schemaVersion,
                    persisted.payload,
                )
                check(decoded is DomainEvent) { "decoded_event_not_domain_event:${persisted.eventType}" }
                state = request.reducer.evolve(state, decoded)
                previousHash = persisted.eventHash
                version = persisted.streamVersion
            }
            ReplayedAggregate(state, version, previousHash)
        }.onSuccess {
            telemetry?.recordReplay("success", replayEvents.size, elapsedSince(startedAt))
        }.onFailure {
            telemetry?.recordReplay("failure", replayEvents.size, elapsedSince(startedAt))
        }.getOrThrow()
    }

    private fun elapsedSince(startedAt: Long): Duration = Duration.ofNanos(System.nanoTime() - startedAt)

    private fun verifyHash(event: PersistedEvent, expectedPreviousHash: String?) {
        val material = EventHashMaterial(
            event.stream, event.streamVersion, event.eventType, event.schemaVersion,
            event.payload, event.metadata, event.previousHash,
        )
        if (event.previousHash != expectedPreviousHash || CanonicalEventHash.sha256(material) != event.eventHash) {
            throw EventHashMismatchException(event.eventId)
        }
    }
}
