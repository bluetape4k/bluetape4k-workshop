package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabaseLane
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedPermitTransactionRunner
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.Duration
import java.util.UUID
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

private val EVENT_STORE_FENCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")

/** Opens independent read transactions while leaving foreground append ownership to the caller. */
internal interface EventStoreTransactionRunner {
    fun <T> readTransaction(block: () -> T): T
}

internal class ExposedEventStoreTransactionRunner(
    private val database: Database,
) : EventStoreTransactionRunner {
    override fun <T> readTransaction(block: () -> T): T = transaction(database) { block() }
}

/** Production reads acquire the foreground permit before asking HikariCP for a connection. */
internal class PermittedEventStoreTransactionRunner(
    database: Database,
    permits: EventSourcedDatabasePermitGate,
) : EventStoreTransactionRunner {
    private val transactions =
        EventSourcedPermitTransactionRunner(
            database,
            permits,
            EventSourcedDatabaseLane.FOREGROUND,
        )

    override fun <T> readTransaction(block: () -> T): T = transactions.inTransaction(block)
}

/**
 * PostgreSQL event authority. Appends deliberately use the caller's transaction so command
 * idempotency finalization and event persistence share one commit boundary.
 */
@Suppress("TooManyFunctions")
internal class EventStoreRepository(
    private val reads: EventStoreTransactionRunner,
    private val metrics: EventStoreAppendMetrics = EventStoreAppendMetrics.NONE,
) : EventStorePort {

    override fun load(read: EventStoreRead): EventPage = reads.readTransaction { loadInCurrentTransaction(read) }

    override fun loadInCurrentTransaction(read: EventStoreRead): EventPage {
        TransactionManager.current()
        val events =
            EventLog
                .selectAll()
                .where {
                    (EventLog.tenantId eq read.stream.tenantId.value) and
                        (EventLog.streamType eq read.stream.streamType) and
                        (EventLog.streamId eq read.stream.streamId) and
                        (EventLog.streamVersion greater read.afterVersion)
                }.orderBy(EventLog.streamVersion to SortOrder.ASC)
                .limit(read.limit)
                .map(::toEnvelope)
        return EventPage(events, headVersion(read.stream))
    }

    override fun appendAll(appends: List<ExpectedAppend>): AppendResult {
        TransactionManager.current()
        val validAppends = appends.requireNotEmpty("appends").requireNotNull("appends")

        val ordered = validAppends.sortedBy(ExpectedAppend::stream)
        ordered.map(ExpectedAppend::stream).toSet().size.requireEquals(ordered.size, "uniqueStreams.size")
        val eventIds = ordered.flatMap { it.events }.map(EventToAppend::eventId)
        eventIds.toSet().size.requireEquals(eventIds.size, "uniqueEventIds.size")

        val streamHeadStarted = TimeSource.Monotonic.markNow()
        val heads = ordered.associate { append -> append.stream to lockHead(append.stream) }
        metrics.streamHeadWait(streamHeadStarted.elapsedNow().toJavaDuration())
        val conflict = ordered.firstOrNull { append -> heads.getValue(append.stream) != append.expectedVersion }
        if (conflict != null) {
            return AppendResult.Conflict(
                stream = conflict.stream,
                expectedVersion = conflict.expectedVersion,
                actualVersion = heads.getValue(conflict.stream),
            )
        }
        return appendCommitted(ordered, eventIds)
    }

    private fun appendCommitted(
        ordered: List<ExpectedAppend>,
        eventIds: List<UUID>,
    ): AppendResult {
        val appendFenceStarted = TimeSource.Monotonic.markNow()
        val firstPosition = lockFence()
        metrics.appendFenceWait(appendFenceStarted.elapsedNow().toJavaDuration())
        eventIds.firstOrNull(::eventAlreadyRecorded)?.let { return AppendResult.DuplicateEvent(it) }
        reserveGlobalPositions(firstPosition, eventIds.size)
        var globalPosition = firstPosition
        val now = Instant.now()
        ordered.forEach { append ->
            append.events.forEachIndexed { index, event ->
                val envelope = event.toEnvelope(append.stream, append.expectedVersion + index + 1L, globalPosition, now)
                EventLog.insert { row ->
                    row[EventLog.id] = envelope.eventId
                    row[EventLog.tenantId] = envelope.tenantId.value
                    row[EventLog.streamType] = envelope.stream.type
                    row[EventLog.streamId] = envelope.stream.id
                    row[EventLog.streamVersion] = envelope.stream.version
                    row[EventLog.globalPosition] = envelope.globalPosition
                    row[EventLog.eventType] = envelope.eventType
                    row[EventLog.schemaVersion] = envelope.schemaVersion
                    row[EventLog.occurredAt] = envelope.occurredAt
                    row[EventLog.recordedAt] = envelope.recordedAt
                    row[EventLog.correlationId] = envelope.correlationId
                    row[EventLog.causationId] = envelope.causationId
                    row[EventLog.actorSurrogate] = envelope.actorSurrogate
                    row[EventLog.actorHmacKeyVersion] = envelope.actorHmacKeyVersion
                    row[EventLog.payload] = envelope.payload.canonicalJson
                    row[EventLog.canonicalChecksum] = envelope.canonicalChecksum
                }
                globalPosition += 1
            }
            StreamHeads.update(where = { streamHeadPredicate(append.stream) }) { row ->
                row[StreamHeads.version] = append.nextVersion()
                row[StreamHeads.updatedAt] = now
            }
        }

        return AppendResult.Appended(
            finalVersions = ordered.associate { append -> append.stream to append.nextVersion() },
            firstGlobalPosition = firstPosition,
            lastGlobalPosition = globalPosition - 1,
        )
    }

    private fun lockHead(stream: StreamKey): Long {
        StreamHeads.insertIgnore { row ->
            row[StreamHeads.streamId] = stream.streamId
            row[StreamHeads.tenantId] = stream.tenantId.value
            row[StreamHeads.streamType] = stream.streamType
            row[StreamHeads.version] = 0
            row[StreamHeads.updatedAt] = Instant.now()
        }
        return StreamHeads
            .selectAll()
            .where { streamHeadPredicate(stream) }
            .forUpdate()
            .single()[StreamHeads.version]
    }

    private fun headVersion(stream: StreamKey): Long =
        StreamHeads
            .selectAll()
            .where { streamHeadPredicate(stream) }
            .singleOrNull()
            ?.get(StreamHeads.version)
            ?: 0

    private fun streamHeadPredicate(stream: StreamKey) =
        (StreamHeads.tenantId eq stream.tenantId.value) and
            (StreamHeads.streamType eq stream.streamType) and
            (StreamHeads.streamId eq stream.streamId)

    private fun eventAlreadyRecorded(eventId: UUID): Boolean =
        EventLog.selectAll().where { EventLog.id eq eventId }.limit(1).any()

    private fun lockFence(): Long {
        AppendFences.insertIgnore { row ->
            row[AppendFences.id] = EVENT_STORE_FENCE_ID
            row[AppendFences.nextGlobalPosition] = 1
        }
        val fence =
            AppendFences
                .selectAll()
                .where { AppendFences.id eq EVENT_STORE_FENCE_ID }
                .forUpdate()
                .single()
        return fence[AppendFences.nextGlobalPosition]
    }

    private fun reserveGlobalPositions(
        firstPosition: Long,
        eventCount: Int,
    ) {
        AppendFences.update(where = { AppendFences.id eq EVENT_STORE_FENCE_ID }) { row ->
            row[AppendFences.nextGlobalPosition] = firstPosition + eventCount
        }
    }

    private fun EventToAppend.toEnvelope(
        stream: StreamKey,
        streamVersion: Long,
        globalPosition: Long,
        recordedAt: Instant,
    ) =
        io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope(
            eventId = eventId,
            tenantId = stream.tenantId,
            stream =
                io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.StreamReference(
                    stream.streamType,
                    stream.streamId,
                    streamVersion,
                ),
            globalPosition = globalPosition,
            eventType = eventType,
            schemaVersion = schemaVersion,
            occurredAt = occurredAt,
            recordedAt = recordedAt,
            correlationId = correlationId,
            causationId = causationId,
            actorSurrogate = actorSurrogate,
            payload = payload,
            actorHmacKeyVersion = actorHmacKeyVersion,
        )

    private fun toEnvelope(row: org.jetbrains.exposed.v1.core.ResultRow) =
        io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope(
            eventId = row[EventLog.id].value,
            tenantId = io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId(row[EventLog.tenantId]),
            stream =
                io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.StreamReference(
                    row[EventLog.streamType],
                    row[EventLog.streamId],
                    row[EventLog.streamVersion],
                ),
            globalPosition = row[EventLog.globalPosition],
            eventType = row[EventLog.eventType],
            schemaVersion = row[EventLog.schemaVersion],
            occurredAt = row[EventLog.occurredAt],
            recordedAt = row[EventLog.recordedAt],
            correlationId = row[EventLog.correlationId],
            causationId = row[EventLog.causationId],
            actorSurrogate = row[EventLog.actorSurrogate],
            payload = io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload(row[EventLog.payload]),
            actorHmacKeyVersion = row[EventLog.actorHmacKeyVersion],
        )
}

internal interface EventStoreAppendMetrics {
    fun streamHeadWait(duration: Duration)

    fun appendFenceWait(duration: Duration)

    companion object {
        val NONE =
            object : EventStoreAppendMetrics {
                override fun streamHeadWait(duration: Duration) = Unit

                override fun appendFenceWait(duration: Duration) = Unit
            }
    }
}
