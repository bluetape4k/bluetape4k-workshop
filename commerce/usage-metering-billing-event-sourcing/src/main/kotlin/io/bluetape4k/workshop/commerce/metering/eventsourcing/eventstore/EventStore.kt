package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.NewEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey

class OptimisticConcurrencyException(
    val stream: StreamKey,
    val expectedVersion: Long,
    val actualVersion: Long,
) : IllegalStateException(
        "stream_version_conflict:${stream.canonical()}:expected=$expectedVersion:actual=$actualVersion",
    )

data class StreamAppend(
    val stream: StreamKey,
    val expectedVersion: Long,
    val events: List<NewEvent>,
)

interface EventStore {
    fun append(stream: StreamKey, expectedVersion: Long, events: List<NewEvent>): List<PersistedEvent>
    fun appendAll(appends: List<StreamAppend>): List<PersistedEvent>
    fun load(stream: StreamKey, afterVersion: Long = 0): List<PersistedEvent>
    fun loadAfterGlobalPosition(afterPosition: Long, limit: Int): List<PersistedEvent>
}
