package io.bluetape4k.workshop.exposed.webflux.r2dbc.order.repository

import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.OrderLineDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.OrderLineTable
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.OrderStatus
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.OrderTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
class OrderRepository {

    fun findAll(): Flow<OrderDTO> =
        OrderTable.selectAll().map { it.toOrderDTO() }

    fun findById(id: Long): Flow<OrderDTO> =
        OrderTable.selectAll().where { OrderTable.id eq id }.map { it.toOrderDTO() }

    suspend fun findByIdOrNull(id: Long): OrderDTO? =
        findById(id).firstOrNull()

    suspend fun findByIdWithLines(id: Long): OrderDTO? {
        val order = findByIdOrNull(id) ?: return null
        val lines = OrderLineTable.selectAll()
            .where { OrderLineTable.orderId eq id }
            .map { it.toOrderLineDTO() }
            .toList()
        return order.copy(lines = lines)
    }

    suspend fun insert(req: PlaceOrderRequest): OrderDTO {
        val stmt = OrderTable.insert {
            it[customerId] = req.customerId
            it[status] = OrderStatus.PENDING
        }
        val newId = stmt[OrderTable.id]
        return findByIdOrNull(newId)!!
    }

    suspend fun updateStatus(id: Long, status: OrderStatus): Int =
        OrderTable.update({ OrderTable.id eq id }) {
            it[OrderTable.status] = status
        }

    private fun ResultRow.toOrderDTO() = OrderDTO(
        id = this[OrderTable.id],
        customerId = this[OrderTable.customerId],
        orderDate = this[OrderTable.orderDate],
        status = this[OrderTable.status],
        lines = emptyList(),
    )

    private fun ResultRow.toOrderLineDTO() = OrderLineDTO(
        id = this[OrderLineTable.id],
        orderId = this[OrderLineTable.orderId],
        productId = this[OrderLineTable.productId],
        quantity = this[OrderLineTable.quantity],
        unitPrice = this[OrderLineTable.unitPrice],
    )
}
