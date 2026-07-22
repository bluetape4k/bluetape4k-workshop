@file:Suppress("MagicNumber") // Column lengths are explicit persistence contracts.

package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

object EventStreamHeads : UUIDTable("metering_event_stream_heads", "head_id") {
    val tenantId = varchar("tenant_id", 64)
    val streamType = varchar("stream_type", 64)
    val streamId = varchar("stream_id", 128)
    val streamVersion = long("stream_version").default(0L)
    val latestEventHash = varchar("latest_event_hash", 64).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(tenantId, streamType, streamId)
    }
}

object DomainEvents : UUIDTable("metering_domain_events", "event_id") {
    val tenantId = varchar("tenant_id", 64)
    val streamType = varchar("stream_type", 64)
    val streamId = varchar("stream_id", 128)
    val streamVersion = long("stream_version")
    val globalPosition = long("global_position").autoIncrement()
    val eventType = varchar("event_type", 128)
    val schemaVersion = integer("schema_version")
    val payload = text("payload")
    val metadata = text("metadata")
    val previousHash = varchar("previous_hash", 64).nullable()
    val eventHash = varchar("event_hash", 64)
    val occurredAt = timestamp("occurred_at")
    val recordedAt = timestamp("recorded_at")

    init {
        uniqueIndex(tenantId, streamType, streamId, streamVersion)
        uniqueIndex(globalPosition)
        index(false, tenantId, globalPosition)
        index(false, tenantId, eventType, occurredAt, id)
    }
}

val METERING_EVENT_TABLES = arrayOf(EventStreamHeads, DomainEvents)
