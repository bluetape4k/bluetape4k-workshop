package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

import java.time.Instant
import java.util.UUID

data class StreamKey(
    val tenantId: String,
    val streamType: String,
    val streamId: String,
) : Comparable<StreamKey> {
    init {
        require(tenantId.isNotBlank() && tenantId.length <= MAX_TENANT_LENGTH) { "tenant_id_invalid" }
        require(streamType.isNotBlank() && streamType.length <= MAX_STREAM_TYPE_LENGTH) { "stream_type_invalid" }
        require(streamId.isNotBlank() && streamId.length <= MAX_STREAM_ID_LENGTH) { "stream_id_invalid" }
    }

    fun canonical(): String = "$tenantId/$streamType/$streamId"

    override fun compareTo(other: StreamKey): Int =
        compareValuesBy(this, other, StreamKey::tenantId, StreamKey::streamType, StreamKey::streamId)

    private companion object {
        const val MAX_TENANT_LENGTH = 64
        const val MAX_STREAM_TYPE_LENGTH = 64
        const val MAX_STREAM_ID_LENGTH = 128
    }
}

sealed interface DomainEvent {
    val eventType: String
    val schemaVersion: Int
}

data class NewEvent(
    val eventId: UUID,
    val event: DomainEvent,
    val payload: String,
    val metadata: String,
    val occurredAt: Instant,
)

data class PersistedEvent(
    val eventId: UUID,
    val stream: StreamKey,
    val streamVersion: Long,
    val globalPosition: Long,
    val eventType: String,
    val schemaVersion: Int,
    val payload: String,
    val metadata: String,
    val previousHash: String?,
    val eventHash: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
) {
    init {
        require(streamVersion > 0) { "stream_version_invalid" }
        require(globalPosition > 0) { "global_position_invalid" }
        require(eventType.isNotBlank()) { "event_type_invalid" }
        require(schemaVersion > 0) { "schema_version_invalid" }
    }
}

data class EventHashMaterial(
    val stream: StreamKey,
    val streamVersion: Long,
    val eventType: String,
    val schemaVersion: Int,
    val payload: String,
    val metadata: String,
    val previousHash: String?,
)
