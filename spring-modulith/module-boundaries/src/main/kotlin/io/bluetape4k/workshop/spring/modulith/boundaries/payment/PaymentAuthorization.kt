package io.bluetape4k.workshop.spring.modulith.boundaries.payment

import java.io.Serializable

/**
 * order event 가 authorize 된 뒤 생성되는 payment module state 입니다.
 */
data class PaymentAuthorization(
    val orderId: String,
    val customerId: String,
    val amountCents: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
