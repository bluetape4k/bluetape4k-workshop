package io.bluetape4k.workshop.messaging.fallback.api

import io.bluetape4k.workshop.messaging.fallback.publication.EventPublicationRecord
import io.bluetape4k.workshop.messaging.fallback.publication.EventPublicationStatus
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Safe read model for demo publication state.
 */
data class PublicationResponse(
    val eventId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val status: EventPublicationStatus,
    val directAttemptCount: Int,
    val relayRetryCount: Int,
    val lastErrorCode: String?,
    val lastErrorSummary: String?,
    val nextAttemptAt: LocalDateTime,
    val publishedAt: LocalDateTime?,
    val updatedAt: LocalDateTime,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(record: EventPublicationRecord): PublicationResponse =
            PublicationResponse(
                eventId = record.eventId,
                aggregateType = record.aggregateType,
                aggregateId = record.aggregateId,
                eventType = record.eventType,
                status = record.status,
                directAttemptCount = record.directAttemptCount,
                relayRetryCount = record.relayRetryCount,
                lastErrorCode = record.lastErrorCode,
                lastErrorSummary = record.lastErrorSummary,
                nextAttemptAt = record.nextAttemptAt,
                publishedAt = record.publishedAt,
                updatedAt = record.updatedAt,
            )
    }
}
