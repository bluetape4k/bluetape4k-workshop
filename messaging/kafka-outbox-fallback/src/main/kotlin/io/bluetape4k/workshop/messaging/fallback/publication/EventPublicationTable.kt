package io.bluetape4k.workshop.messaging.fallback.publication

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Fallback table that stores only failed or reconstructed publications.
 */
object EventPublicationTable : LongIdTable("event_publications") {
    val eventId = varchar("event_id", 160).uniqueIndex()
    val aggregateType = varchar("aggregate_type", 80)
    val aggregateId = varchar("aggregate_id", 80)
    val eventType = varchar("event_type", 80)
    val payload = text("payload")
    val status = enumerationByName("status", 20, EventPublicationStatus::class)
        .default(EventPublicationStatus.NOT_PUBLISHED)
    val directAttemptCount = integer("direct_attempt_count").default(0)
    val relayRetryCount = integer("relay_retry_count").default(0)
    val lastErrorCode = varchar("last_error_code", 80).nullable()
    val lastErrorSummary = varchar("last_error_summary", 240).nullable()
    val nextAttemptAt = datetime("next_attempt_at").defaultExpression(CurrentDateTime)
    val claimedBy = varchar("claimed_by", 120).nullable()
    val claimedUntil = datetime("claimed_until").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val publishedAt = datetime("published_at").nullable()
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
}
