package io.bluetape4k.workshop.spring.modulith.boundaries.ordering.events

import java.io.Serializable
import java.time.Instant

/**
 * Event contract exported by the ordering module after an order is accepted.
 */
data class OrderPlacedEvent(
    val orderId: String,
    val customerId: String,
    val sku: String,
    val quantity: Int,
    val totalCents: Long,
    val placedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
