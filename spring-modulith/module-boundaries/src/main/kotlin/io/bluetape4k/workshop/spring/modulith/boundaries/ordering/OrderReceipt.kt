package io.bluetape4k.workshop.spring.modulith.boundaries.ordering

import java.io.Serializable

/**
 * ordering module 이 주문을 수락한 뒤 반환하는 synchronous response 입니다.
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
