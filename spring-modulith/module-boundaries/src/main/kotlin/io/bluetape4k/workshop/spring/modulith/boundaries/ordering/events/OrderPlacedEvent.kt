package io.bluetape4k.workshop.spring.modulith.boundaries.ordering.events

import java.io.Serializable
import java.time.Instant

/**
 * 주문 수락 후 ordering module 이 export 하는 event contract 입니다.
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
