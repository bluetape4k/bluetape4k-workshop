package io.bluetape4k.workshop.spring.modulith.boundaries.ordering

import java.io.Serializable

/**
 * Synchronous response returned after the ordering module accepts an order.
 */
data class OrderReceipt(
    val orderId: String,
    val sku: String,
    val quantity: Int,
    val totalCents: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
