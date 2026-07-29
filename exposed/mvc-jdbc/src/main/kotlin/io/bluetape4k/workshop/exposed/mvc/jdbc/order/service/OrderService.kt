package io.bluetape4k.workshop.exposed.mvc.jdbc.order.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.CreateProductRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.OrderDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.OrderWithLinesDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.PlaceOrderRequest
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.dto.ProductDTO
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.exception.InsufficientStockException
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.repository.OrderLineRepository
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.repository.OrderRepository
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.repository.ProductRepository
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.OrderStatus
import io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema.OrderTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class OrderService(
    private val orderRepo: OrderRepository,
    private val orderLineRepo: OrderLineRepository,
    private val productRepo: ProductRepository,
) {
    companion object : KLogging()

    @Transactional(readOnly = true)
    fun findAll(): List<OrderDTO> = orderRepo.findAll()

    @Transactional(readOnly = true)
    fun findById(id: Long): OrderDTO =
        orderRepo.findById(id) ?: throw NoSuchElementException("Order $id not found")

    @Transactional(readOnly = true)
    fun findWithLines(id: Long): OrderWithLinesDTO {
        val order = findById(id)
        val lines = orderLineRepo.findByOrderId(id)
        return OrderWithLinesDTO(order, lines)
    }

    @Transactional(readOnly = true)
    fun calculateTotal(id: Long): BigDecimal {
        val lines = orderLineRepo.findByOrderId(id)
        return lines.fold(BigDecimal.ZERO) { acc, line ->
            acc + (line.unitPrice * BigDecimal(line.quantity))
        }
    }

    @Transactional
    fun placeOrder(req: PlaceOrderRequest): OrderDTO {
        val order = orderRepo.insert(req)
        // deadlock 방지: productId 오름차순으로 정렬한다.
        val sortedLines = req.lines.sortedBy { it.productId }
        for (line in sortedLines) {
            val product = productRepo.findByIdForUpdate(line.productId)
                ?: throw NoSuchElementException("Product ${line.productId} not found")
            if (product.stock < line.quantity) {
                throw InsufficientStockException(line.productId)
            }
            orderLineRepo.insert(order.id, line, product.price)
            productRepo.decrementStock(line.productId, line.quantity)
        }
        return orderRepo.findById(order.id) ?: order
    }

    @Transactional
    fun cancelOrder(id: Long): OrderDTO {
        val rows = OrderTable.update({ OrderTable.id eq id }) {
            it[status] = OrderStatus.CANCELLED
        }
        if (rows == 0) throw NoSuchElementException("Order $id not found")
        return orderRepo.findById(id) ?: throw NoSuchElementException("Order $id not found after cancel")
    }

    @Transactional
    fun createProduct(req: CreateProductRequest): ProductDTO = productRepo.insert(req)

    @Transactional(readOnly = true)
    fun findAllProducts(): List<ProductDTO> = productRepo.findAll()

    @Transactional(readOnly = true)
    fun findProductById(id: Long): ProductDTO =
        productRepo.findById(id) ?: throw NoSuchElementException("Product $id not found")
}
