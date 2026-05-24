package io.bluetape4k.workshop.messaging.outbox.domain

/**
 * Represents the lifecycle status of an [Order].
 *
 * ## States
 * - [PENDING]   — order placed, not yet confirmed
 * - [CONFIRMED] — order accepted by the system
 * - [SHIPPED]   — order dispatched
 * - [CANCELLED] — order cancelled before shipment
 */
enum class OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPED,
    CANCELLED,
}
