package io.bluetape4k.workshop.gateway.orders.model

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.math.BigDecimal

data class Order(
    val orderNumber: String,
    val amount: BigDecimal,
    val customerName: String,
): Serializable {
    init {
        orderNumber.requireNotBlank("orderNumber")
        amount.requirePositiveNumber("amount")
        customerName.requireNotBlank("customerName")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
