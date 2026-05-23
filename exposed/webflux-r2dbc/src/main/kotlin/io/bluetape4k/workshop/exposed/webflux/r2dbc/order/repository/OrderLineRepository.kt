package io.bluetape4k.workshop.exposed.webflux.r2dbc.order.repository

import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.OrderLineDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.OrderLineRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.OrderLineTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
class OrderLineRepository {

    fun findByOrderId(orderId: Long): Flow<OrderLineDTO> =
        OrderLineTable.selectAll()
            .where { OrderLineTable.orderId eq orderId }
            .map { it.toOrderLineDTO() }

    suspend fun insert(orderId: Long, line: OrderLineRequest, unitPrice: BigDecimal): OrderLineDTO {
        val stmt = OrderLineTable.insert {
            it[OrderLineTable.orderId] = orderId
            it[productId] = line.productId
            it[quantity] = line.quantity
            it[OrderLineTable.unitPrice] = unitPrice
        }
        return OrderLineDTO(
            id = stmt[OrderLineTable.id],
            orderId = orderId,
            productId = line.productId,
            quantity = line.quantity,
            unitPrice = unitPrice,
        )
    }

    private fun ResultRow.toOrderLineDTO() = OrderLineDTO(
        id = this[OrderLineTable.id],
        orderId = this[OrderLineTable.orderId],
        productId = this[OrderLineTable.productId],
        quantity = this[OrderLineTable.quantity],
        unitPrice = this[OrderLineTable.unitPrice],
    )
}
