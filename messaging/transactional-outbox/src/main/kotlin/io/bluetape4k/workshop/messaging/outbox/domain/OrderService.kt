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
 * [Order] lifecycle operation 을 담당하는 domain service 입니다.
 *
 * 모든 mutating method 는 단일 database transaction 안에서 domain row 와 [OutboxEventTable] row 를 함께 씁니다. 이것이 Transactional Outbox pattern 입니다. scheduler([io.bluetape4k.workshop.messaging.outbox.outbox.OutboxPublisher]) 는 outbox table 을 polling 하고 pending event 를 Kafka 로 비동기 publish 합니다.
 */
@Service
@Transactional
class OrderService(
    private val objectMapper: ObjectMapper,
) {
    companion object : KLogging()

    /**
     * 새 order 를 place 하고 `OrderPlaced` outbox event 를 atomically 기록합니다.
     *
     * @param customerId non-blank customer identifier 입니다.
     * @param product non-blank product name 입니다.
     * @param quantity 양수 unit count 입니다.
     * @return persisted order 를 반영하는 [OrderResponse] 입니다.
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
     * order status 를 update 하고 `OrderStatusChanged` outbox event 를 atomically 기록합니다.
     *
     * @param orderId update 할 order 입니다.
     * @param status 새 [OrderStatus] 입니다.
     * @return updated order 를 반영하는 [OrderResponse] 입니다.
     * @throws NoSuchElementException [orderId] 에 해당하는 order 가 없으면 발생합니다.
     */
    fun updateStatus(orderId: Long, status: OrderStatus): OrderResponse {
        val validOrderId = orderId.requirePositiveNumber("orderId")
        val now = LocalDateTime.now()
        val rows = OrderTable.update({ OrderTable.id eq validOrderId }) {
            it[OrderTable.status] = status
            it[OrderTable.updatedAt] = now
        }
        if (rows == 0) throw NoSuchElementException("Order $validOrderId not found")

        log.debug { "Updated order id=$validOrderId status=$status" }

        val payloadMap = mapOf(
            "orderId" to validOrderId,
            "status" to status.name,
        )
        val payload = objectMapper.writeValueAsString(payloadMap)

        OutboxEventTable.insert {
            it[OutboxEventTable.aggregateType] = "Order"
            it[OutboxEventTable.aggregateId] = validOrderId.toString()
            it[OutboxEventTable.eventType] = "OrderStatusChanged"
            it[OutboxEventTable.payload] = payload
            it[OutboxEventTable.status] = OutboxStatus.PENDING
        }

        return getOrderResponse(validOrderId)
    }

    /**
     * id 로 단일 order 를 조회합니다.
     *
     * @throws NoSuchElementException [orderId] 에 해당하는 order 가 없으면 발생합니다.
     */
    @Transactional(readOnly = true)
    fun getOrder(orderId: Long): OrderResponse =
        getOrderResponse(orderId.requirePositiveNumber("orderId"))

    /** 모든 order 를 id ascending 순서로 반환합니다. */
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

    // ── 내부 helper ───────────────────────────────────────────────────────────

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
