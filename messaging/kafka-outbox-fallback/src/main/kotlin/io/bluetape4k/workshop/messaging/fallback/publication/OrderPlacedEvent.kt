package io.bluetape4k.workshop.messaging.fallback.publication

import io.bluetape4k.workshop.messaging.fallback.domain.OrderRecord
import io.bluetape4k.workshop.messaging.fallback.domain.OrderStatus
import java.io.Serializable
import java.time.LocalDateTime

/**
 * order placement 를 위한 typed integration event 입니다.
 */
data class OrderPlacedEvent(
    val orderId: Long,
    val customerId: String,
    val product: String,
    val quantity: Int,
    val status: OrderStatus,
    val createdAt: LocalDateTime,
) : Serializable {
    val eventId: String get() = "order-placed:$orderId:v1"

    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(order: OrderRecord): OrderPlacedEvent =
            OrderPlacedEvent(
                orderId = order.id,
                customerId = order.customerId,
                product = order.product,
                quantity = order.quantity,
                status = order.status,
                createdAt = order.createdAt,
            )
    }
}
