package io.bluetape4k.workshop.messaging.outbox.api

import io.bluetape4k.workshop.messaging.outbox.domain.OrderStatus
import java.io.Serializable
import java.time.LocalDateTime

/**
 * HTTP response body representing a single order.
 *
 * @property id         Primary key of the order.
 * @property customerId Identifier of the ordering customer.
 * @property product    Product name.
 * @property quantity   Number of units ordered.
 * @property status     Current [OrderStatus].
 * @property createdAt  Wall-clock time of order creation.
 * @property updatedAt  Wall-clock time of last status change.
 */
data class OrderResponse(
    val id: Long,
    val customerId: String,
    val product: String,
    val quantity: Int,
    val status: OrderStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
