package io.bluetape4k.workshop.exposed.mvc.jdbc.order.repository

import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.CreateProductRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.ProductDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.mapper.toProductDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.ProductTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository

@Repository
class ProductRepository {

    fun findAll(): List<ProductDTO> =
        ProductTable.selectAll().map { it.toProductDTO() }

    fun findById(id: Long): ProductDTO? =
        ProductTable.selectAll()
            .where { ProductTable.id eq id }
            .singleOrNull()
            ?.toProductDTO()

    fun findByIdForUpdate(id: Long): ProductDTO? =
        ProductTable.selectAll()
            .where { ProductTable.id eq id }
            .forUpdate()
            .singleOrNull()
            ?.toProductDTO()

    fun insert(req: CreateProductRequest): ProductDTO {
        val id = ProductTable.insert {
            it[name] = req.name
            it[price] = req.price
            it[stock] = req.stock
        }[ProductTable.id]
        return findById(id) ?: throw NoSuchElementException("Product $id not found after insert")
    }

    fun decrementStock(id: Long, quantity: Int) {
        ProductTable.update({ ProductTable.id eq id }) {
            it[ProductTable.stock] = ProductTable.stock - quantity
        }
    }
}
