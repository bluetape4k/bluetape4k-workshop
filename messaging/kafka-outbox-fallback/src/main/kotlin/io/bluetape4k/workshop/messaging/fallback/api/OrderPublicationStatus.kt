package io.bluetape4k.workshop.messaging.fallback.api

/**
 * Caller-facing publication outcome for an order placement request.
 *
 * This is deliberately separate from the fallback table lifecycle state so the
 * REST API does not expose internal relay statuses as create-order outcomes.
 */
enum class OrderPublicationStatus {
    PUBLISHED_DIRECT,
    FALLBACK_STORED,
    FALLBACK_STORE_FAILED,
    UNKNOWN,
}
