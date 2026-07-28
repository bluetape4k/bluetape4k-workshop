package io.bluetape4k.workshop.messaging.fallback.domain

import java.io.Serializable
import java.time.LocalDateTime

/**
 * order row 를 위한 internal immutable projection 입니다.
 */
data class OrderRecord(
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
