package io.bluetape4k.workshop.spring.modulith.boundaries.ordering

import java.io.Serializable

/**
 * ordering module 에서 주문을 생성하기 위한 command object 입니다.
 */
data class OrderRequest(
    val sku: String,
    val quantity: Int,
    val customerId: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
