package io.bluetape4k.workshop.messaging.fallback.publication

/**
 * Durable fallback publication lifecycle state.
 */
enum class EventPublicationStatus {
    NOT_PUBLISHED,
    PUBLISHED,
    FAILED,
    DEAD_LETTER,
}
