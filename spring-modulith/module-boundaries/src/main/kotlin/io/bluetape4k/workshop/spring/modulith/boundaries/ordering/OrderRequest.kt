package io.bluetape4k.workshop.spring.modulith.boundaries.ordering

import java.io.Serializable

/**
 * Command object for placing an order from the ordering module.
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
