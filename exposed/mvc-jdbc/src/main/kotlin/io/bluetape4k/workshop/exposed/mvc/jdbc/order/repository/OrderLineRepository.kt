package io.bluetape4k.workshop.exposed.mvc.jdbc.order.repository

import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.OrderLineDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.OrderLineRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.mapper.toOrderLineDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.OrderLineTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
class OrderLineRepository {

    fun findByOrderId(orderId: Long): List<OrderLineDTO> =
        OrderLineTable.selectAll()
            .where { OrderLineTable.orderId eq orderId }
            .map { it.toOrderLineDTO() }

    fun insert(orderId: Long, line: OrderLineRequest, unitPrice: BigDecimal): OrderLineDTO {
        val id = OrderLineTable.insert {
            it[this.orderId] = orderId
            it[productId] = line.productId
            it[quantity] = line.quantity
            it[this.unitPrice] = unitPrice
        }[OrderLineTable.id]
        return OrderLineTable.selectAll()
            .where { OrderLineTable.id eq id }
            .single()
            .toOrderLineDTO()
    }
}
