package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import java.time.Instant
import java.util.UUID

internal const val MAX_EVENT_STORE_PAGE_SIZE = 200
private const val UUID_V7 = 7

/** A globally unique aggregate stream within the event authority. */
internal data class StreamKey(
    val tenantId: TenantId,
    val streamType: String,
    val streamId: UUID,
) : Comparable<StreamKey> {
    init {
        streamType.requireNotBlank("streamType")
    }

    override fun compareTo(other: StreamKey): Int =
        compareValuesBy(this, other, { it.tenantId.value }, StreamKey::streamType, StreamKey::streamId)
}

/** The event material known before the event store assigns stream and global positions. */
internal data class EventToAppend(
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val payload: EventPayload,
    val occurredAt: Instant = Instant.now(),
    val correlationId: String = eventId.toString(),
    val causationId: String? = null,
    val actorSurrogate: String = "system",
) {
    init {
        require(eventId.version() == UUID_V7) { "eventId must be UUID v7" }
        eventType.requireNotBlank("eventType")
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        correlationId.requireNotBlank("correlationId")
        actorSurrogate.requireNotBlank("actorSurrogate")
    }
}

internal data class ExpectedAppend(
    val stream: StreamKey,
    val expectedVersion: Long,
    val events: List<EventToAppend>,
) {
    init {
        require(expectedVersion >= 0) { "expectedVersion must be non-negative" }
        require(events.isNotEmpty()) { "events must not be empty" }
        require(events.map(EventToAppend::eventId).toSet().size == events.size) {
            "events must not repeat an eventId"
        }
    }
}

/** A bounded request for a stream tail. */
internal data class EventStoreRead(
    val stream: StreamKey,
    val afterVersion: Long,
    val limit: Int = MAX_EVENT_STORE_PAGE_SIZE,
) {
    init {
        require(afterVersion >= 0) { "afterVersion must be non-negative" }
        require(limit in 1..MAX_EVENT_STORE_PAGE_SIZE) {
            "limit must be between 1 and $MAX_EVENT_STORE_PAGE_SIZE"
        }
    }
}

internal data class EventPage(
    val events: List<EventEnvelope>,
    val committedHead: Long,
) {
    init {
        require(events.size <= MAX_EVENT_STORE_PAGE_SIZE) { "event page exceeds the hard cap" }
        require(committedHead >= 0) { "committedHead must be non-negative" }
    }
}

internal sealed interface AppendResult {
    data class Appended(
        val finalVersions: Map<StreamKey, Long>,
        val firstGlobalPosition: Long,
        val lastGlobalPosition: Long,
    ) : AppendResult {
        init {
            require(finalVersions.isNotEmpty()) { "finalVersions must not be empty" }
            require(firstGlobalPosition > 0) { "firstGlobalPosition must be positive" }
            require(lastGlobalPosition >= firstGlobalPosition) { "global positions must be ordered" }
        }
    }

    data class Conflict(
        val stream: StreamKey,
        val expectedVersion: Long,
        val actualVersion: Long,
    ) : AppendResult

    data class DuplicateEvent(val eventId: UUID) : AppendResult
}

internal interface EventStorePort {
    fun load(read: EventStoreRead): EventPage

    fun appendAll(appends: List<ExpectedAppend>): AppendResult
}

internal fun ExpectedAppend.nextVersion(): Long = expectedVersion + events.size
