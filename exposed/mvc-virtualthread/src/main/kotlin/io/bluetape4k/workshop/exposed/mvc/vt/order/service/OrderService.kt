package io.bluetape4k.workshop.exposed.mvc.vt.order.service

import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.CreateProductRequest
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.OrderWithLinesDTO
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.mvc.vt.order.dto.ProductDTO
import io.bluetape4k.workshop.exposed.mvc.vt.order.exception.InsufficientStockException
import io.bluetape4k.workshop.exposed.mvc.vt.order.mapper.toOrderDTO
import io.bluetape4k.workshop.exposed.mvc.vt.order.repository.OrderLineRepository
import io.bluetape4k.workshop.exposed.mvc.vt.order.repository.OrderRepository
import io.bluetape4k.workshop.exposed.mvc.vt.order.repository.ProductRepository
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.OrderLineTable
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.OrderStatus
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.OrderTable
import io.bluetape4k.workshop.exposed.mvc.vt.order.schema.ProductTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ExecutorService

@Service
class OrderService(
    private val db: Database,
    private val executor: ExecutorService,
    private val orderRepo: OrderRepository,
    private val orderLineRepo: OrderLineRepository,
    private val productRepo: ProductRepository,
) {
    companion object : KLogging()

    fun findAll(): List<OrderDTO> = orderRepo.findAll().get()

    fun findById(id: Long): OrderDTO =
        orderRepo.findById(id).get() ?: throw NoSuchElementException("Order $id not found")

    fun findWithLines(id: Long): OrderWithLinesDTO {
        val order = findById(id)
        val lines = orderLineRepo.findByOrderId(id).get()
        return OrderWithLinesDTO(order, lines)
    }

    fun calculateTotal(id: Long): BigDecimal {
        val lines = orderLineRepo.findByOrderId(id).get()
        return lines.fold(BigDecimal.ZERO) { acc, line ->
            acc + (line.unitPrice * BigDecimal(line.quantity))
        }
    }

    fun placeOrder(req: PlaceOrderRequest): OrderDTO = virtualFuture(executor) {
        transaction(db) {
            val orderId = OrderTable.insert {
                it[customerId] = req.customerId
            }[OrderTable.id]

            // deadlock 방지: productId 오름차순으로 정렬한다.
            val sortedLines = req.lines.sortedBy { it.productId }
            for (line in sortedLines) {
                val productRow = ProductTable.selectAll()
                    .where { ProductTable.id eq line.productId }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NoSuchElementException("Product ${line.productId} not found")

                if (productRow[ProductTable.stock] < line.quantity) {
                    throw InsufficientStockException(line.productId)
                }

                OrderLineTable.insert {
                    it[this.orderId] = orderId
                    it[productId] = line.productId
                    it[quantity] = line.quantity
                    it[unitPrice] = productRow[ProductTable.price]
                }

                ProductTable.update({ ProductTable.id eq line.productId }) {
                    it[ProductTable.stock] = ProductTable.stock - line.quantity
                }
            }

            OrderTable.selectAll()
                .where { OrderTable.id eq orderId }
                .single()
                .toOrderDTO()
        }
    }.get()

    fun cancelOrder(orderId: Long): OrderDTO = virtualFuture(executor) {
        transaction(db) {
            val rows = OrderTable.update({ OrderTable.id eq orderId }) {
                it[status] = OrderStatus.CANCELLED
            }
            if (rows == 0) throw NoSuchElementException("Order $orderId not found")
            OrderTable.selectAll()
                .where { OrderTable.id eq orderId }
                .single()
                .toOrderDTO()
        }
    }.get()

    fun createProduct(req: CreateProductRequest): ProductDTO = productRepo.insert(req).get()

    fun findAllProducts(): List<ProductDTO> = productRepo.findAll().get()

    fun findProductById(id: Long): ProductDTO =
        productRepo.findById(id).get() ?: throw NoSuchElementException("Product $id not found")
}
