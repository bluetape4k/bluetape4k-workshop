package io.bluetape4k.workshop.messaging.fallback.api

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.io.Serializable

/**
 * Request body for placing a workshop order.
 */
data class OrderRequest(
    @field:NotBlank
    @field:Size(max = 80)
    val customerId: String,

    @field:NotBlank
    @field:Size(max = 120)
    val product: String,

    @field:Min(1)
    @field:Max(1000)
    val quantity: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
