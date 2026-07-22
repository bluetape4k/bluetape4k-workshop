package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.NewEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.CanonicalEventHash
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OptimisticConcurrencyException
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.StreamAppend
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Clock
import java.util.UUID

@Repository
class EventStoreRepository(
    private val clock: Clock = Clock.systemUTC(),
) : AppendOnlyEventSourcingExposedJdbcRepository<DomainEventEntity, UUID>(DomainEventEntity::class.java), EventStore {

    override fun appendAll(appends: List<StreamAppend>): List<PersistedEvent> {
        require(appends.isNotEmpty()) { "appends_empty" }
        require(appends.map(StreamAppend::stream).distinct().size == appends.size) { "duplicate_stream_append" }
        return appends.sortedBy(StreamAppend::stream)
            .flatMap { append -> append(append.stream, append.expectedVersion, append.events) }
    }

    override fun append(stream: StreamKey, expectedVersion: Long, events: List<NewEvent>): List<PersistedEvent> {
        require(expectedVersion >= 0) { "expected_version_invalid" }
        require(events.isNotEmpty()) { "events_empty" }
        val now = clock.instant()
        val head = lockHead(stream, now)
        if (head.version != expectedVersion) {
            throw OptimisticConcurrencyException(stream, expectedVersion, head.version)
        }

        var previousHash = head.latestHash
        val appended = events.mapIndexed { index, event ->
            insertEvent(stream, expectedVersion + index + 1L, event, previousHash, now)
                .also { previousHash = it.eventHash }
        }
        updateHead(stream, expectedVersion, events.size, previousHash, now)
        return appended
    }

    private fun lockHead(stream: StreamKey, now: java.time.Instant): LockedHead {
        EventStreamHeads.insertIgnore {
            it[id] = UUID.randomUUID()
            it[tenantId] = stream.tenantId
            it[streamType] = stream.streamType
            it[streamId] = stream.streamId
            it[streamVersion] = 0L
            it[latestEventHash] = null
            it[createdAt] = now
            it[updatedAt] = now
        }
        val head = EventStreamHeads.selectAll().where {
            (EventStreamHeads.tenantId eq stream.tenantId) and
                (EventStreamHeads.streamType eq stream.streamType) and
                (EventStreamHeads.streamId eq stream.streamId)
        }.forUpdate().single()
        return LockedHead(head[EventStreamHeads.streamVersion], head[EventStreamHeads.latestEventHash])
    }

    private fun insertEvent(
        stream: StreamKey,
        version: Long,
        event: NewEvent,
        previousHash: String?,
        now: java.time.Instant,
    ): PersistedEvent {
        val hash = CanonicalEventHash.sha256(
            EventHashMaterial(
                stream, version, event.event.eventType, event.event.schemaVersion,
                event.payload, event.metadata, previousHash,
            ),
        )
        val insert = DomainEvents.insert {
            it[id] = event.eventId
            it[tenantId] = stream.tenantId
            it[streamType] = stream.streamType
            it[streamId] = stream.streamId
            it[streamVersion] = version
            it[eventType] = event.event.eventType
            it[schemaVersion] = event.event.schemaVersion
            it[payload] = event.payload
            it[metadata] = event.metadata
            it[DomainEvents.previousHash] = previousHash
            it[eventHash] = hash
            it[occurredAt] = event.occurredAt
            it[recordedAt] = now
        }
        return PersistedEvent(
            event.eventId, stream, version, insert[DomainEvents.globalPosition],
            event.event.eventType, event.event.schemaVersion, event.payload, event.metadata,
            previousHash, hash, event.occurredAt, now,
        )
    }

    private fun updateHead(
        stream: StreamKey,
        expectedVersion: Long,
        appendedCount: Int,
        latestHash: String?,
        now: java.time.Instant,
    ) {
        val updated = EventStreamHeads.update(
            where = {
                (EventStreamHeads.tenantId eq stream.tenantId) and
                    (EventStreamHeads.streamType eq stream.streamType) and
                    (EventStreamHeads.streamId eq stream.streamId) and
                    (EventStreamHeads.streamVersion eq expectedVersion)
            },
        ) {
            it[streamVersion] = expectedVersion + appendedCount
            it[latestEventHash] = latestHash
            it[updatedAt] = now
        }
        check(updated == 1) { "stream_head_fencing_failed" }
    }

    override fun load(stream: StreamKey, afterVersion: Long): List<PersistedEvent> =
        DomainEvents.selectAll()
            .where {
                (DomainEvents.tenantId eq stream.tenantId) and
                    (DomainEvents.streamType eq stream.streamType) and
                    (DomainEvents.streamId eq stream.streamId) and
                    (DomainEvents.streamVersion greater afterVersion)
            }
            .orderBy(DomainEvents.streamVersion to SortOrder.ASC)
            .map { row ->
                PersistedEvent(
                    eventId = row[DomainEvents.id].value,
                    stream = stream,
                    streamVersion = row[DomainEvents.streamVersion],
                    globalPosition = row[DomainEvents.globalPosition],
                    eventType = row[DomainEvents.eventType],
                    schemaVersion = row[DomainEvents.schemaVersion],
                    payload = row[DomainEvents.payload],
                    metadata = row[DomainEvents.metadata],
                    previousHash = row[DomainEvents.previousHash],
                    eventHash = row[DomainEvents.eventHash],
                    occurredAt = row[DomainEvents.occurredAt],
                    recordedAt = row[DomainEvents.recordedAt],
                )
            }

    override fun loadAfterGlobalPosition(afterPosition: Long, limit: Int): List<PersistedEvent> {
        require(afterPosition >= 0) { "global_position_invalid" }
        require(limit in 1..MAX_EVENT_PAGE_SIZE) { "event_page_size_invalid" }
        return DomainEvents.selectAll()
            .where { DomainEvents.globalPosition greater afterPosition }
            .orderBy(DomainEvents.globalPosition to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                val stream = StreamKey(
                    row[DomainEvents.tenantId],
                    row[DomainEvents.streamType],
                    row[DomainEvents.streamId],
                )
                PersistedEvent(
                    eventId = row[DomainEvents.id].value,
                    stream = stream,
                    streamVersion = row[DomainEvents.streamVersion],
                    globalPosition = row[DomainEvents.globalPosition],
                    eventType = row[DomainEvents.eventType],
                    schemaVersion = row[DomainEvents.schemaVersion],
                    payload = row[DomainEvents.payload],
                    metadata = row[DomainEvents.metadata],
                    previousHash = row[DomainEvents.previousHash],
                    eventHash = row[DomainEvents.eventHash],
                    occurredAt = row[DomainEvents.occurredAt],
                    recordedAt = row[DomainEvents.recordedAt],
                )
            }
    }

    private data class LockedHead(val version: Long, val latestHash: String?)

    private companion object {
        const val MAX_EVENT_PAGE_SIZE = 1_000
    }
}
