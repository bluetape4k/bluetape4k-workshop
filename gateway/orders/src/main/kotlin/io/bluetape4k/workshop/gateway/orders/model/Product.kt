package io.bluetape4k.workshop.gateway.orders.model

import java.io.Serializable
import java.math.BigDecimal

data class Product(
    val id: String,
    val name: String,
    val price: BigDecimal,
): Serializable
