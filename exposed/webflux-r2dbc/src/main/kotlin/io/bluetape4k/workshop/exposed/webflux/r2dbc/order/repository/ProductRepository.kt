package io.bluetape4k.workshop.exposed.webflux.r2dbc.order.repository

import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.CreateProductRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.ProductDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.ProductTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.springframework.stereotype.Repository

@Repository
class ProductRepository {

    fun findAll(): Flow<ProductDTO> =
        ProductTable.selectAll().map { it.toProductDTO() }

    fun findById(id: Long): Flow<ProductDTO> =
        ProductTable.selectAll().where { ProductTable.id eq id }.map { it.toProductDTO() }

    suspend fun findByIdOrNull(id: Long): ProductDTO? =
        findById(id).firstOrNull()

    suspend fun findByIdForUpdate(id: Long): ProductDTO? =
        ProductTable.selectAll()
            .where { ProductTable.id eq id }
            .forUpdate(ForUpdateOption.ForUpdate)
            .map { it.toProductDTO() }
            .firstOrNull()

    suspend fun insert(req: CreateProductRequest): Long {
        val stmt = ProductTable.insert {
            it[name] = req.name
            it[price] = req.price
            it[stock] = req.stock
        }
        return stmt[ProductTable.id]
    }

    suspend fun decrementStock(id: Long, quantity: Int) {
        ProductTable.update({ ProductTable.id eq id }) {
            it[ProductTable.stock] = ProductTable.stock - quantity
        }
    }

    private fun ResultRow.toProductDTO() = ProductDTO(
        id = this[ProductTable.id],
        name = this[ProductTable.name],
        price = this[ProductTable.price],
        stock = this[ProductTable.stock],
    )
}
