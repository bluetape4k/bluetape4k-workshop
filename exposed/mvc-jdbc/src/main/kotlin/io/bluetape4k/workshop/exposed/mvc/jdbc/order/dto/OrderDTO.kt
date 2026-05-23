package io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto

import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.OrderStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.io.Serializable
import java.math.BigDecimal

data class ProductDTO(
    val id: Long = 0,
    val name: String = "",
    val price: BigDecimal = BigDecimal.ZERO,
    val stock: Int = 0,
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}

data class OrderDTO(
    val id: Long = 0,
    val customerId: Long = 0,
    val orderDate: Long = 0,
    val status: OrderStatus = OrderStatus.PENDING,
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}

data class OrderLineDTO(
    val id: Long = 0,
    val orderId: Long = 0,
    val productId: Long = 0,
    val quantity: Int = 0,
    val unitPrice: BigDecimal = BigDecimal.ZERO,
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}

data class OrderWithLinesDTO(
    val order: OrderDTO,
    val lines: List<OrderLineDTO> = emptyList(),
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}

data class CreateProductRequest(
    @field:NotEmpty val name: String = "",
    @field:Positive val price: BigDecimal = BigDecimal.ZERO,
    @field:Min(0) val stock: Int = 0,
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}

data class PlaceOrderRequest(
    @field:Positive val customerId: Long = 0,
    @field:Valid @field:NotEmpty @field:Size(max = 100) val lines: List<OrderLineRequest> = emptyList(),
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}

data class OrderLineRequest(
    @field:Positive val productId: Long = 0,
    @field:Min(1) val quantity: Int = 1,
) : Serializable {
    companion object {
        const val serialVersionUID = 1L
    }
}
