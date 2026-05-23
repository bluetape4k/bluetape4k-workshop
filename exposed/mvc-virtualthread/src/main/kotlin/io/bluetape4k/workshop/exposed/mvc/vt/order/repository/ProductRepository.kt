package io.bluetape4k.workshop.exposed.mvc.vt.order.repository

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.CreateProductRequest
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.ProductDTO
import io.bluetape4k.workshop.exposed.mvc.vt.order.mapper.toProductDTO
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.ProductTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.util.concurrent.ExecutorService

@Repository
class ProductRepository(
    private val db: Database,
    private val executor: ExecutorService,
) {
    companion object : KLogging()

    fun findAll(): VirtualFuture<List<ProductDTO>> = virtualFuture(executor) {
        transaction(db) {
            ProductTable.selectAll().map { it.toProductDTO() }
        }
    }

    fun findById(id: Long): VirtualFuture<ProductDTO?> = virtualFuture(executor) {
        transaction(db) {
            ProductTable.selectAll()
                .where { ProductTable.id eq id }
                .singleOrNull()
                ?.toProductDTO()
        }
    }

    fun insert(req: CreateProductRequest): VirtualFuture<ProductDTO> = virtualFuture(executor) {
        transaction(db) {
            val newId = ProductTable.insert {
                it[name] = req.name
                it[price] = req.price
                it[stock] = req.stock
            }[ProductTable.id]
            ProductTable.selectAll()
                .where { ProductTable.id eq newId }
                .single()
                .toProductDTO()
        }
    }
}
