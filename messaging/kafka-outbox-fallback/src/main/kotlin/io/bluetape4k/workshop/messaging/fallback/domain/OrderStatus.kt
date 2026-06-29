package io.bluetape4k.workshop.messaging.fallback.domain

/**
 * Minimal order state for the fallback outbox workshop flow.
 */
enum class OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
}
