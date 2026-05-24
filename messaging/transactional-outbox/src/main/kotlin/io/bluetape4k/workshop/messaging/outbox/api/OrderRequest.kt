package io.bluetape4k.workshop.messaging.outbox.api

import io.bluetape4k.workshop.messaging.outbox.domain.OrderStatus
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.io.Serializable

/**
 * HTTP request body for placing a new order.
 *
 * @property customerId Identifier of the ordering customer; must not be blank.
 * @property product    Product name; must not be blank.
 * @property quantity   Number of units; must be at least 1.
 */
data class OrderRequest(
    @field:NotBlank val customerId: String,
    @field:NotBlank val product: String,
    @field:Min(1) val quantity: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * HTTP request body for updating an order's status.
 *
 * @property status The new [OrderStatus] to transition the order to.
 */
data class UpdateStatusRequest(
    val status: OrderStatus,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
