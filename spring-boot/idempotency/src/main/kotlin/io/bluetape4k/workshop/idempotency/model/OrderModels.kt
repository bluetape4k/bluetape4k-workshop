package io.bluetape4k.workshop.idempotency.model

import java.io.Serializable
import java.time.Instant

/**
 * Request payload for creating an order.
 *
 * ## Behavior / Contract
 * - [productId] must be non-blank.
 * - [quantity] must be positive.
 * - [userId] must be non-blank.
 */
data class OrderRequest(
    val productId: String,
    val quantity: Int,
    val userId: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Response returned for a successfully processed (or replayed) order.
 *
 * ## Behavior / Contract
 * - First creation returns HTTP 201.
 * - Replayed requests with the same Idempotency-Key return HTTP 200 with the same body.
 */
data class OrderResponse(
    val orderId: String,
    val status: String,
    val processedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Envelope stored in the idempotency cache, combining HTTP status with response body.
 *
 * ## Behavior / Contract
 * - [httpStatus] is the status code returned on first creation (e.g., 201).
 * - [response] is the original [OrderResponse] payload.
 */
data class CachedResponse(
    val httpStatus: Int,
    val response: OrderResponse,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
