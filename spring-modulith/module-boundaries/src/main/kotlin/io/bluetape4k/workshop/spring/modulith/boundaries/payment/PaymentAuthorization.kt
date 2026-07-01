package io.bluetape4k.workshop.spring.modulith.boundaries.payment

import java.io.Serializable

/**
 * Payment module state created after an order event is authorized.
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
