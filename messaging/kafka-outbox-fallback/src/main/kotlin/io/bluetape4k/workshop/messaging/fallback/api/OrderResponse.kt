package io.bluetape4k.workshop.messaging.fallback.api

import io.bluetape4k.workshop.messaging.fallback.domain.OrderRecord
import io.bluetape4k.workshop.messaging.fallback.domain.OrderStatus
import java.io.Serializable
import java.time.LocalDateTime

/**
 * REST response for an order and its caller-facing publication outcome.
 */
data class OrderResponse(
    val id: Long,
    val customerId: String,
    val product: String,
    val quantity: Int,
    val status: OrderStatus,
    val publicationStatus: OrderPublicationStatus,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun from(record: OrderRecord, publicationStatus: OrderPublicationStatus): OrderResponse =
            OrderResponse(
                id = record.id,
                customerId = record.customerId,
                product = record.product,
                quantity = record.quantity,
                status = record.status,
                publicationStatus = publicationStatus,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
            )
    }
}
