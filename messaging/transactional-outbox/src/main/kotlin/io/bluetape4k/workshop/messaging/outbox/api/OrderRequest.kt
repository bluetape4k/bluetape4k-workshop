package io.bluetape4k.workshop.messaging.outbox.api

import io.bluetape4k.workshop.messaging.outbox.domain.OrderStatus
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.io.Serializable

/**
 * 새 order 를 place 하기 위한 HTTP request body 입니다.
 *
 * @property customerId 주문 customer 의 identifier 입니다. blank 일 수 없습니다.
 * @property product product name 입니다. blank 일 수 없습니다.
 * @property quantity 주문 unit 수입니다. 최소 1이어야 합니다.
 */
data class OrderRequest(
    @field:NotBlank val customerId: String,
    @field:NotBlank val product: String,
    @field:Min(1) val quantity: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * order status 를 update 하기 위한 HTTP request body 입니다.
 *
 * @property status order 가 transition 할 새 [OrderStatus] 입니다.
 */
data class UpdateStatusRequest(
    val status: OrderStatus,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
