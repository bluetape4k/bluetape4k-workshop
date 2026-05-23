package io.bluetape4k.workshop.observability.basic.model

import java.io.Serializable

/**
 * Represents a placed order with resolved inventory availability.
 *
 * ## Behavior / Contract
 * - `inventoryAvailable` reflects the count fetched from the downstream inventory service.
 * - This is a read-only view model returned by `OrderService.getOrder`.
 */
data class Order(
    val id: Long,
    val itemId: Long,
    val quantity: Int,
    val inventoryAvailable: Int,
) : Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
