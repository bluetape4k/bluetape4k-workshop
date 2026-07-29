package io.bluetape4k.workshop.messaging.fallback.publication

/**
 * durable fallback publication lifecycle state 입니다.
 */
enum class EventPublicationStatus {
    NOT_PUBLISHED,
    PUBLISHED,
    FAILED,
    DEAD_LETTER,
}
