package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import java.time.Instant
import java.util.UUID

internal const val MAX_EVENT_STORE_PAGE_SIZE = 200
private const val UUID_V7 = 7

/** event authority 안에서 전역적으로 유일한 aggregate stream입니다. */
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

/** event store가 stream position과 global position을 배정하기 전에 알고 있는 event material입니다. */
internal data class EventToAppend(
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val payload: EventPayload,
    val occurredAt: Instant = Instant.now(),
    val correlationId: String = eventId.toString(),
    val causationId: String? = null,
    val actorSurrogate: String = "system",
    val actorHmacKeyVersion: Int = 1,
) {
    init {
        eventId.version().requireEquals(UUID_V7, "eventId.version")
        eventType.requireNotBlank("eventType")
        schemaVersion.requirePositiveNumber("schemaVersion")
        correlationId.requireNotBlank("correlationId")
        actorSurrogate.requireNotBlank("actorSurrogate")
        actorHmacKeyVersion.requirePositiveNumber("actorHmacKeyVersion")
    }
}

internal data class ExpectedAppend(
    val stream: StreamKey,
    val expectedVersion: Long,
    val events: List<EventToAppend>,
) {
    init {
        expectedVersion.requireZeroOrPositiveNumber("expectedVersion")
        events.requireNotEmpty("events")
        events.map(EventToAppend::eventId).toSet().size.requireEquals(events.size, "uniqueEventIds.size")
    }
}

/** stream tail을 위한 bounded request입니다. */
internal data class EventStoreRead(
    val stream: StreamKey,
    val afterVersion: Long,
    val limit: Int = MAX_EVENT_STORE_PAGE_SIZE,
) {
    init {
        afterVersion.requireZeroOrPositiveNumber("afterVersion")
        limit.requireInRange(1, MAX_EVENT_STORE_PAGE_SIZE, "limit")
    }
}

internal data class EventPage(
    val events: List<EventEnvelope>,
    val committedHead: Long,
) {
    init {
        events.size.requireLe(MAX_EVENT_STORE_PAGE_SIZE, "events.size")
        committedHead.requireZeroOrPositiveNumber("committedHead")
    }
}

internal sealed interface AppendResult {
    data class Appended(
        val finalVersions: Map<StreamKey, Long>,
        val firstGlobalPosition: Long,
        val lastGlobalPosition: Long,
    ) : AppendResult {
        init {
            finalVersions.requireNotEmpty("finalVersions")
            firstGlobalPosition.requirePositiveNumber("firstGlobalPosition")
            lastGlobalPosition.requireGe(firstGlobalPosition, "lastGlobalPosition")
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

    /**
     * command orchestration이 이미 소유한 transaction을 통해 load합니다.
     *
     * 구현체는 두 번째 transaction을 열거나 nested database permit을 얻지 않도록 이를 override할 수 있습니다.
     * test double은 기본적으로 일반 bounded load를 사용합니다.
     */
    fun loadInCurrentTransaction(read: EventStoreRead): EventPage = load(read)

    fun appendAll(appends: List<ExpectedAppend>): AppendResult
}

internal fun ExpectedAppend.nextVersion(): Long = expectedVersion + events.size
