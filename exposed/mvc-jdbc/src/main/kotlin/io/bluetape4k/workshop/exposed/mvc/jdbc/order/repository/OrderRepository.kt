package io.bluetape4k.workshop.exposed.mvc.jdbc.order.repository

import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.mapper.toOrderDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.OrderTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

@Repository
class OrderRepository {

    fun findAll(): List<OrderDTO> =
        OrderTable.selectAll().map { it.toOrderDTO() }

    fun findById(id: Long): OrderDTO? =
        OrderTable.selectAll()
            .where { OrderTable.id eq id }
            .singleOrNull()
            ?.toOrderDTO()

    fun insert(req: PlaceOrderRequest): OrderDTO {
        val id = OrderTable.insert {
            it[customerId] = req.customerId
        }[OrderTable.id]
        return findById(id) ?: throw NoSuchElementException("Order $id not found after insert")
    }
}
