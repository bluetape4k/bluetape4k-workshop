package io.bluetape4k.workshop.messaging.outbox.outbox

/**
 * Represents the processing status of an [OutboxEvent].
 *
 * ## States
 * - [PENDING]     — event created, not yet published to Kafka
 * - [PUBLISHED]   — event successfully sent to Kafka
 * - [FAILED]      — last publish attempt failed; eligible for retry
 * - [DEAD_LETTER] — exceeded [OutboxPublisher.MAX_RETRY]; no further retries
 */
enum class OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
    DEAD_LETTER,
}
