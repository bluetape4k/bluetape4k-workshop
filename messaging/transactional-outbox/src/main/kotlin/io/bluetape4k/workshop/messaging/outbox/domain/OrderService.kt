package io.bluetape4k.workshop.messaging.outbox.domain

import tools.jackson.databind.ObjectMapper
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.messaging.outbox.api.OrderResponse
import io.bluetape4k.workshop.messaging.outbox.outbox.OutboxStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Domain service for [Order] lifecycle operations.
 *
 * Every mutating method writes both the domain row and an [OutboxEventTable] row inside
 * a single database transaction — the Transactional Outbox pattern.  The scheduler
 * ([io.bluetape4k.workshop.messaging.outbox.outbox.OutboxPublisher]) polls the outbox
 * table and publishes pending events to Kafka asynchronously.
 */
@Service
@Transactional
class OrderService(
    private val objectMapper: ObjectMapper,
) {
    companion object : KLogging()

    /**
     * Places a new order and records an `OrderPlaced` outbox event atomically.
     *
     * @param customerId Non-blank customer identifier.
     * @param product    Non-blank product name.
     * @param quantity   Positive unit count.
     * @return [OrderResponse] reflecting the persisted order.
     */
    fun placeOrder(customerId: String, product: String, quantity: Int): OrderResponse {
        customerId.requireNotBlank("customerId")
        product.requireNotBlank("product")
        quantity.requirePositiveNumber("quantity")

        val orderId = OrderTable.insertAndGetId {
            it[OrderTable.customerId] = customerId
            it[OrderTable.product] = product
            it[OrderTable.quantity] = quantity
            it[OrderTable.status] = OrderStatus.PENDING
        }

        log.debug { "Placed order id=${orderId.value} for customer=$customerId product=$product qty=$quantity" }

        val payloadMap = mapOf(
            "orderId" to orderId.value,
            "customerId" to customerId,
            "product" to product,
            "quantity" to quantity,
            "status" to OrderStatus.PENDING.name,
        )
        val payload = objectMapper.writeValueAsString(payloadMap)

        OutboxEventTable.insert {
            it[OutboxEventTable.aggregateType] = "Order"
            it[OutboxEventTable.aggregateId] = orderId.value.toString()
            it[OutboxEventTable.eventType] = "OrderPlaced"
            it[OutboxEventTable.payload] = payload
            it[OutboxEventTable.status] = OutboxStatus.PENDING
        }

        return getOrderResponse(orderId.value)
    }

    /**
     * Updates an order's status and records an `OrderStatusChanged` outbox event atomically.
     *
     * @param orderId The order to update.
     * @param status  The new [OrderStatus].
     * @return [OrderResponse] reflecting the updated order.
     * @throws NoSuchElementException if no order exists with [orderId].
     */
    fun updateStatus(orderId: Long, status: OrderStatus): OrderResponse {
        val now = LocalDateTime.now()
        val rows = OrderTable.update({ OrderTable.id eq orderId }) {
            it[OrderTable.status] = status
            it[OrderTable.updatedAt] = now
        }
        if (rows == 0) throw NoSuchElementException("Order $orderId not found")

        log.debug { "Updated order id=$orderId status=$status" }

        val payloadMap = mapOf(
            "orderId" to orderId,
            "status" to status.name,
        )
        val payload = objectMapper.writeValueAsString(payloadMap)

        OutboxEventTable.insert {
            it[OutboxEventTable.aggregateType] = "Order"
            it[OutboxEventTable.aggregateId] = orderId.toString()
            it[OutboxEventTable.eventType] = "OrderStatusChanged"
            it[OutboxEventTable.payload] = payload
            it[OutboxEventTable.status] = OutboxStatus.PENDING
        }

        return getOrderResponse(orderId)
    }

    /**
     * Retrieves a single order by id.
     *
     * @throws NoSuchElementException if no order exists with [orderId].
     */
    @Transactional(readOnly = true)
    fun getOrder(orderId: Long): OrderResponse = getOrderResponse(orderId)

    /** Returns all orders, ordered by id ascending. */
    @Transactional(readOnly = true)
    fun getAllOrders(): List<OrderResponse> =
        OrderTable.selectAll().orderBy(OrderTable.id to SortOrder.ASC).map { row ->
            OrderResponse(
                id = row[OrderTable.id].value,
                customerId = row[OrderTable.customerId],
                product = row[OrderTable.product],
                quantity = row[OrderTable.quantity],
                status = row[OrderTable.status],
                createdAt = row[OrderTable.createdAt],
                updatedAt = row[OrderTable.updatedAt],
            )
        }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun getOrderResponse(orderId: Long): OrderResponse {
        val row = OrderTable.selectAll()
            .where { OrderTable.id eq orderId }
            .singleOrNull()
            ?: throw NoSuchElementException("Order $orderId not found")

        return OrderResponse(
            id = row[OrderTable.id].value,
            customerId = row[OrderTable.customerId],
            product = row[OrderTable.product],
            quantity = row[OrderTable.quantity],
            status = row[OrderTable.status],
            createdAt = row[OrderTable.createdAt],
            updatedAt = row[OrderTable.updatedAt],
        )
    }
}
