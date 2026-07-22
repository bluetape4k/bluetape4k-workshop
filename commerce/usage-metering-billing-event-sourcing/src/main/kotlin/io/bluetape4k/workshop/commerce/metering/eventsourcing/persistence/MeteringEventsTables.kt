@file:Suppress("MagicNumber") // Column lengths are explicit persistence contracts.

package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
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

object CommandReceipts : UUIDTable("metering_command_receipts", "receipt_id") {
    val tenantId = varchar("tenant_id", 64)
    val operation = varchar("operation", 64)
    val keyDigest = varchar("key_digest", 64)
    val fingerprint = varchar("fingerprint", 64)
    val status = varchar("status", 24)
    val ownerToken = javaUUID("owner_token")
    val leaseUntil = timestamp("lease_until")
    val retentionUntil = timestamp("retention_until")
    val httpStatus = integer("http_status").nullable()
    val response = text("response").nullable()
    val terminalAt = timestamp("terminal_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(tenantId, operation, keyDigest)
        index(false, status, retentionUntil, id)
    }
}

object AggregateSnapshots : UUIDTable("metering_aggregate_snapshots", "snapshot_id") {
    val tenantId = varchar("tenant_id", 64)
    val streamType = varchar("stream_type", 64)
    val streamId = varchar("stream_id", 128)
    val streamVersion = long("stream_version")
    val reducerVersion = integer("reducer_version")
    val statePayload = text("state_payload")
    val lastEventHash = varchar("last_event_hash", 64)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, streamType, streamId, streamVersion, reducerVersion)
        index(false, tenantId, streamType, streamId, reducerVersion, streamVersion)
    }
}

object ProjectionAliases : UUIDTable("metering_projection_aliases", "alias_id") {
    val projectionName = varchar("projection_name", 96).uniqueIndex()
    val activeGeneration = integer("active_generation")
    val updatedAt = timestamp("updated_at")
}

object ProjectionGenerations : UUIDTable("metering_projection_generations", "projection_generation_id") {
    val projectionName = varchar("projection_name", 96)
    val generation = integer("generation")
    val state = varchar("state", 16)
    val checkpoint = long("checkpoint").default(0L)
    val highWatermark = long("high_watermark").default(0L)
    val ownerToken = javaUUID("owner_token").nullable()
    val leaseUntil = timestamp("lease_until").nullable()
    val failedPosition = long("failed_position").nullable()
    val failureDigest = varchar("failure_digest", 128).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(projectionName, generation)
        index(false, projectionName, state, generation)
    }
}

object ProjectionAppliedEvents : UUIDTable("metering_projection_applied_events", "marker_id") {
    val projectionName = varchar("projection_name", 96)
    val generation = integer("generation")
    val eventId = javaUUID("event_id")
    val globalPosition = long("global_position")
    val appliedAt = timestamp("applied_at")

    init {
        uniqueIndex(projectionName, generation, eventId)
        index(false, projectionName, generation, globalPosition)
    }
}

val METERING_EVENT_TABLES = arrayOf(
    EventStreamHeads,
    DomainEvents,
    CommandReceipts,
    AggregateSnapshots,
    ProjectionAliases,
    ProjectionGenerations,
    ProjectionAppliedEvents,
)
