package io.bluetape4k.workshop.messaging.outbox.api

import io.bluetape4k.workshop.messaging.outbox.domain.OrderStatus
import java.io.Serializable
import java.time.LocalDateTime

/**
 * 단일 order 를 표현하는 HTTP response body 입니다.
 *
 * @property id order 의 primary key 입니다.
 * @property customerId 주문 customer 의 identifier 입니다.
 * @property product product name 입니다.
 * @property quantity 주문된 unit 수입니다.
 * @property status 현재 [OrderStatus] 입니다.
 * @property createdAt order 생성 wall-clock time 입니다.
 * @property updatedAt 마지막 status change wall-clock time 입니다.
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
