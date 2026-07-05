package io.bluetape4k.workshop.gateway.orders.model

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.math.BigDecimal

data class Product(
    val id: String,
    val name: String,
    val price: BigDecimal,
): Serializable {
    init {
        id.requireNotBlank("id")
        name.requireNotBlank("name")
        price.requirePositiveNumber("price")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
