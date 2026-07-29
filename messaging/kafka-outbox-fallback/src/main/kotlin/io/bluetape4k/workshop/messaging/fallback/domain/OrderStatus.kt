package io.bluetape4k.workshop.messaging.fallback.domain

/**
 * fallback outbox workshop flow 를 위한 최소 order state 입니다.
 */
enum class OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
}
