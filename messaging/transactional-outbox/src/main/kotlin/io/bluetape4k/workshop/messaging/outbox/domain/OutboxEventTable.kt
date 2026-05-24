package io.bluetape4k.workshop.messaging.outbox.domain

import io.bluetape4k.workshop.messaging.outbox.outbox.OutboxStatus
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Exposed table for outbox events used by the Transactional Outbox pattern.
 *
 * Each row is written atomically in the same transaction that mutates the domain
 * aggregate.  A background scheduler ([io.bluetape4k.workshop.messaging.outbox.outbox.OutboxPublisher])
 * polls [OutboxStatus.PENDING] (and [OutboxStatus.FAILED]) rows, publishes them to
 * Kafka, then marks them [OutboxStatus.PUBLISHED].
 *
 * ## Schema
 * - `id`             — auto-increment Long primary key
 * - `aggregate_type` — domain type, e.g. `"Order"`
 * - `aggregate_id`   — string representation of the aggregate PK
 * - `event_type`     — discriminator, e.g. `"OrderPlaced"`, `"OrderStatusChanged"`
 * - `payload`        — JSON-serialised event payload
 * - `status`         — current [OutboxStatus], defaults to [OutboxStatus.PENDING]
 * - `retry_count`    — number of failed publish attempts
 * - `created_at`     — wall-clock time of row creation
 * - `processed_at`   — wall-clock time of successful publish (nullable)
 */
object OutboxEventTable : LongIdTable("outbox_events") {
    val aggregateType = varchar("aggregate_type", 100)
    val aggregateId = varchar("aggregate_id", 100)
    val eventType = varchar("event_type", 100)
    val payload = text("payload")
    val status = enumerationByName("status", 20, OutboxStatus::class).default(OutboxStatus.PENDING)
    val retryCount = integer("retry_count").default(0)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val processedAt = datetime("processed_at").nullable()
}
