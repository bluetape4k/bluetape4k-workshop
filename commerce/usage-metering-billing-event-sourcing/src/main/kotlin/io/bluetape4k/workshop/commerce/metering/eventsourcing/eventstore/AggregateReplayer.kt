package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AggregateReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.ReplayedAggregate

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

class EventHashMismatchException(eventId: java.util.UUID) : IllegalStateException("event_hash_mismatch:$eventId")

object AggregateReplayer {
    fun <S> replay(
        events: List<PersistedEvent>,
        initialState: S,
        reducer: AggregateReducer<S>,
        snapshot: AggregateSnapshotSeed<S>?,
        policy: ReplayPolicy,
    ): ReplayedAggregate<S> {
        val validSnapshot = snapshot?.takeIf { candidate ->
            candidate.reducerVersion == policy.reducerVersion &&
                events.firstOrNull { it.streamVersion == candidate.streamVersion }?.eventHash == candidate.lastEventHash
        }
        var state = validSnapshot?.state ?: initialState
        var previousHash = validSnapshot?.lastEventHash
        var version = validSnapshot?.streamVersion ?: 0L
        events.asSequence().filter { it.streamVersion > version }.forEach { persisted ->
            verifyHash(persisted, previousHash)
            val decoded = policy.registry.decode(persisted.eventType, persisted.schemaVersion, persisted.payload)
            check(decoded is DomainEvent) { "decoded_event_not_domain_event:${persisted.eventType}" }
            state = reducer.evolve(state, decoded)
            previousHash = persisted.eventHash
            version = persisted.streamVersion
        }
        return ReplayedAggregate(state, version, previousHash)
    }

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
