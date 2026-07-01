package io.bluetape4k.workshop.spring.modulith.boundaries.notification

import java.io.Serializable

/**
 * Notification module state created from an order event.
 */
data class NotificationMessage(
    val orderId: String,
    val customerId: String,
    val message: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
