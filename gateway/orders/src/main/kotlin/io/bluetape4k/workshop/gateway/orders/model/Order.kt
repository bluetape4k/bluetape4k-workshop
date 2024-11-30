package io.bluetape4k.workshop.gateway.orders.model

import java.io.Serializable
import java.math.BigDecimal

data class Order(
    val orderNumber: String,
    val amount: BigDecimal,
    val customerName: String,
): Serializable
