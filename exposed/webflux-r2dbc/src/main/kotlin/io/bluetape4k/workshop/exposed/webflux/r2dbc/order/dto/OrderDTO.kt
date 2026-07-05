package io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto

import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.OrderStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.io.Serializable
import java.math.BigDecimal

data class ProductDTO(
    val id: Long = 0L,
    val name: String = "",
    val price: BigDecimal = BigDecimal.ZERO,
    val stock: Int = 0,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class CreateProductRequest(
    @field:NotBlank val name: String = "",
    @field:Positive val price: BigDecimal = BigDecimal.ZERO,
    @field:Min(0) val stock: Int = 0,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class OrderLineDTO(
    val id: Long = 0L,
    val orderId: Long = 0L,
    val productId: Long = 0L,
    val quantity: Int = 0,
    val unitPrice: BigDecimal = BigDecimal.ZERO,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class OrderDTO(
    val id: Long = 0L,
    val customerId: Long = 0L,
    val orderDate: Long = 0L,
    val status: OrderStatus = OrderStatus.PENDING,
    val lines: List<OrderLineDTO> = emptyList(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class OrderLineRequest(
    @field:Positive val productId: Long = 0L,
    @field:Min(1) val quantity: Int = 1,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class PlaceOrderRequest(
    @field:Positive val customerId: Long = 0L,
    @field:NotEmpty @field:Valid @field:Size(max = 100) val lines: List<OrderLineRequest> = emptyList(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
