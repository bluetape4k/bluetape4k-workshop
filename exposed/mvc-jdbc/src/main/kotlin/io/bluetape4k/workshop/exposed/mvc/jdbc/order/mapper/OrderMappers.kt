package io.bluetape4k.workshop.exposed.mvc.jdbc.order.mapper

import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.OrderLineDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.ProductDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.OrderLineTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.OrderTable
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.ProductTable
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toProductDTO() = ProductDTO(
    id = this[ProductTable.id],
    name = this[ProductTable.name],
    price = this[ProductTable.price],
    stock = this[ProductTable.stock],
)

fun ResultRow.toOrderDTO() = OrderDTO(
    id = this[OrderTable.id],
    customerId = this[OrderTable.customerId],
    orderDate = this[OrderTable.orderDate],
    status = this[OrderTable.status],
)

fun ResultRow.toOrderLineDTO() = OrderLineDTO(
    id = this[OrderLineTable.id],
    orderId = this[OrderLineTable.orderId],
    productId = this[OrderLineTable.productId],
    quantity = this[OrderLineTable.quantity],
    unitPrice = this[OrderLineTable.unitPrice],
)
