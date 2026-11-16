package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireLt
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.NewEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.CanonicalEventHash
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventTypeQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OptimisticConcurrencyException
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.StreamAppend
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Clock
import java.util.*

@Repository
class EventStoreRepository(
    private val clock: Clock = Clock.systemUTC(),
): AppendOnlyEventSourcingExposedJdbcRepository<DomainEventEntity, UUID>(DomainEventEntity::class),
   EventStore {

    private companion object: KLogging() {
        const val MAX_EVENT_PAGE_SIZE = 1_000
    }

    override fun appendAll(appends: List<StreamAppend>): List<PersistedEvent> {
        appends.requireNotEmpty("appends")
        require(appends.map(StreamAppend::stream).distinct().size == appends.size) { "duplicate_stream_append" }

        return appends
            .sortedBy(StreamAppend::stream)
            .flatMap { append ->
                append(append.stream, append.expectedVersion, append.events)
            }
    }

    override fun append(stream: StreamKey, expectedVersion: Long, events: List<NewEvent>): List<PersistedEvent> {
        expectedVersion.requireZeroOrPositiveNumber("expectedVersion")
        events.requireNotEmpty("events")

        val now = clock.instant()
        val head = lockHead(stream, now)
        if (head.version != expectedVersion) {
            throw OptimisticConcurrencyException(stream, expectedVersion, head.version)
        }

        var previousHash = head.latestHash
        val appended = events
            .mapIndexed { index, event ->
                insertEvent(
                    stream = stream,
                    version = expectedVersion + index + 1L,
                    event = event,
                    previousHash = previousHash
                ).also { previousHash = it.eventHash }
            }
        updateHead(
            stream = stream,
            expectedVersion = expectedVersion,
            appendedCount = events.size,
            latestHash = previousHash,
            now = now
        )
        return appended
    }

    private fun lockHead(stream: StreamKey, now: java.time.Instant): LockedHead {
        EventStreamHeads.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[tenantId] = stream.tenantId
            it[streamType] = stream.streamType
            it[streamId] = stream.streamId
            it[streamVersion] = 0L
            it[latestEventHash] = null
            it[createdAt] = now
            it[updatedAt] = now
        }
        val head = EventStreamHeads
            .selectAll()
            .where {
                (EventStreamHeads.tenantId eq stream.tenantId) and
                        (EventStreamHeads.streamType eq stream.streamType) and
                        (EventStreamHeads.streamId eq stream.streamId)
            }
            .forUpdate()
            .single()

        return LockedHead(
            version = head[EventStreamHeads.streamVersion],
            latestHash = head[EventStreamHeads.latestEventHash]
        )
    }

    private fun insertEvent(
        stream: StreamKey,
        version: Long,
        event: NewEvent,
        previousHash: String?,
    ): PersistedEvent {
        val hash = CanonicalEventHash.sha256(
            EventHashMaterial(
                stream = stream,
                streamVersion = version,
                eventType = event.event.eventType,
                schemaVersion = event.event.schemaVersion,
                payload = event.payload,
                metadata = event.metadata,
                previousHash = previousHash,
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
        }
        val recordedAt = DomainEvents
            .selectAll()
            .where { DomainEvents.id eq event.eventId }
            .single()[DomainEvents.recordedAt]

        return PersistedEvent(
            eventId = event.eventId,
            stream = stream,
            streamVersion = version,
            globalPosition = insert[DomainEvents.globalPosition],
            eventType = event.event.eventType,
            schemaVersion = event.event.schemaVersion,
            payload = event.payload,
            metadata = event.metadata,
            previousHash = previousHash,
            eventHash = hash,
            occurredAt = event.occurredAt,
            recordedAt = recordedAt,
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
        DomainEvents
            .selectAll()
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

    override fun latestGlobalPosition(): Long =
        DomainEvents
            .selectAll()
            .orderBy(DomainEvents.globalPosition to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(DomainEvents.globalPosition)
            ?: 0L

    override fun loadAfterGlobalPosition(afterPosition: Long, limit: Int): List<PersistedEvent> {
        afterPosition.requireZeroOrPositiveNumber("afterPosition")
        limit.requireInRange(1, MAX_EVENT_PAGE_SIZE, "limit")

        return DomainEvents
            .selectAll()
            .where { DomainEvents.globalPosition greater afterPosition }
            .orderBy(DomainEvents.globalPosition to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                val stream = StreamKey(
                    tenantId = row[DomainEvents.tenantId],
                    streamType = row[DomainEvents.streamType],
                    streamId = row[DomainEvents.streamId],
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

    override fun loadByType(query: EventTypeQuery): List<PersistedEvent> {
        require(query.startsAt < query.endsAt) { "event_range_invalid" }
        query.startsAt.requireLt(query.endsAt) { "query.startsAt must be less than endsAt" }
        query.limit.requireInRange(1, MAX_EVENT_PAGE_SIZE, "query.limit")

        val cursor = query.after?.let {
            (DomainEvents.occurredAt greater it.occurredAt) or
                    ((DomainEvents.occurredAt eq it.occurredAt) and
                            (DomainEvents.id greater it.eventId))
        }
        return DomainEvents.selectAll()
            .where {
                (DomainEvents.tenantId eq query.tenantId) and
                        (DomainEvents.eventType eq query.eventType) and
                        (DomainEvents.occurredAt greaterEq query.startsAt) and
                        (DomainEvents.occurredAt less query.endsAt) and
                        (cursor ?: Op.TRUE)
            }
            .orderBy(
                DomainEvents.occurredAt to SortOrder.ASC,
                DomainEvents.id to SortOrder.ASC,
            )
            .limit(query.limit)
            .map(::toPersistedEvent)
    }

    private fun toPersistedEvent(row: org.jetbrains.exposed.v1.core.ResultRow): PersistedEvent {
        val stream = StreamKey(
            tenantId = row[DomainEvents.tenantId],
            streamType = row[DomainEvents.streamType],
            streamId = row[DomainEvents.streamId]
        )

        return PersistedEvent(
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

    private data class LockedHead(
        val version: Long,
        val latestHash: String?,
    )
}
