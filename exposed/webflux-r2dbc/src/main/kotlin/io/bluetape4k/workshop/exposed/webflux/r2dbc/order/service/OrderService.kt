package io.bluetape4k.workshop.exposed.webflux.r2dbc.order.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.CreateProductRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.dto.ProductDTO
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.repository.OrderLineRepository
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.repository.OrderRepository
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.repository.ProductRepository
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.OrderStatus
import io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema.OrderTable
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.springframework.stereotype.Service

class InsufficientStockException(productId: Long) :
    RuntimeException("Insufficient stock for product $productId")

@Service
class OrderService(
    private val orderRepo: OrderRepository,
    private val orderLineRepo: OrderLineRepository,
    private val productRepo: ProductRepository,
    private val db: R2dbcDatabase,
) {
    companion object : KLoggingChannel()

    suspend fun findAllProducts(): List<ProductDTO> = suspendTransaction(db = db) {
        productRepo.findAll().toList()
    }

    suspend fun findProductById(id: Long): ProductDTO = suspendTransaction(db = db) {
        productRepo.findByIdOrNull(id)
            ?: throw NoSuchElementException("Product $id not found")
    }

    suspend fun createProduct(req: CreateProductRequest): ProductDTO = suspendTransaction(db = db) {
        val newId = productRepo.insert(req)
        productRepo.findByIdOrNull(newId)
            ?: throw NoSuchElementException("Product $newId not found after insert")
    }

    suspend fun findAllOrders(): List<OrderDTO> = suspendTransaction(db = db) {
        orderRepo.findAll().toList()
    }

    suspend fun findOrderById(id: Long): OrderDTO = suspendTransaction(db = db) {
        orderRepo.findByIdWithLines(id)
            ?: throw NoSuchElementException("Order $id not found")
    }

    suspend fun placeOrder(req: PlaceOrderRequest): OrderDTO = suspendTransaction(db = db) {
        val order = orderRepo.insert(req)
        // Sort by productId to prevent deadlock
        val sortedLines = req.lines.sortedBy { it.productId }
        for (line in sortedLines) {
            val product = productRepo.findByIdForUpdate(line.productId)
                ?: throw NoSuchElementException("Product ${line.productId} not found")
            if (product.stock < line.quantity) throw InsufficientStockException(line.productId)
            orderLineRepo.insert(order.id, line, product.price)
            productRepo.decrementStock(line.productId, line.quantity)
        }
        orderRepo.findByIdWithLines(order.id) ?: order
    }

    suspend fun cancelOrder(id: Long): OrderDTO = suspendTransaction(db = db) {
        val rows = OrderTable.update({ OrderTable.id eq id }) {
            it[status] = OrderStatus.CANCELLED
        }
        if (rows == 0) throw NoSuchElementException("Order $id not found")
        orderRepo.findByIdWithLines(id)
            ?: throw NoSuchElementException("Order $id not found after cancel")
    }
}
