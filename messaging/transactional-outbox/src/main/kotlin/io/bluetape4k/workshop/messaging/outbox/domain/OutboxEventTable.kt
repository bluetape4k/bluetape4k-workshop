package io.bluetape4k.workshop.messaging.outbox.domain

import io.bluetape4k.workshop.messaging.outbox.outbox.OutboxStatus
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Transactional Outbox pattern 이 사용하는 outbox event 용 Exposed table 입니다.
 *
 * 각 row 는 domain aggregate 를 mutate 하는 동일 transaction 안에서 atomically 기록됩니다. background scheduler([io.bluetape4k.workshop.messaging.outbox.outbox.OutboxPublisher]) 는 [OutboxStatus.PENDING] 및 [OutboxStatus.FAILED] row 를 polling 하고 Kafka 로 publish 한 뒤 [OutboxStatus.PUBLISHED] 로 표시합니다.
 *
 * ## Schema
 * - `id` — auto-increment Long primary key 입니다.
 * - `aggregate_type` — domain type 입니다. 예: `"Order"`
 * - `aggregate_id` — aggregate PK 의 string representation 입니다.
 * - `event_type` — discriminator 입니다. 예: `"OrderPlaced"`, `"OrderStatusChanged"`
 * - `payload` — JSON-serialized event payload 입니다.
 * - `status` — 현재 [OutboxStatus] 입니다. 기본값은 [OutboxStatus.PENDING] 입니다.
 * - `retry_count` — failed publish attempt 수입니다.
 * - `created_at` — row creation wall-clock time 입니다.
 * - `processed_at` — successful publish wall-clock time 이며 nullable 입니다.
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
