package io.bluetape4k.workshop.messaging.outbox.outbox

import java.io.Serializable
import java.time.LocalDateTime

/**
 * DTO projection of an [io.bluetape4k.workshop.messaging.outbox.domain.OutboxEventTable] row.
 *
 * Carried by [OutboxPublisher] from the database to Kafka.
 *
 * @property id            Primary key from `outbox_events`
 * @property aggregateType Domain type, e.g. `"Order"`
 * @property aggregateId   String representation of the aggregate PK
 * @property eventType     Discriminator string, e.g. `"OrderPlaced"`
 * @property payload       JSON-serialised event body
 * @property status        Current [OutboxStatus]
 * @property retryCount    Number of failed publish attempts
 * @property createdAt     Row creation time
 * @property processedAt   Time of successful publish, or null
 */
data class OutboxEvent(
    val id: Long,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
    val status: OutboxStatus,
    val retryCount: Int,
    val createdAt: LocalDateTime,
    val processedAt: LocalDateTime?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
