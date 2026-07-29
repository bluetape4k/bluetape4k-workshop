package io.bluetape4k.workshop.messaging.outbox.api

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.messaging.outbox.domain.OrderService
import io.bluetape4k.workshop.messaging.outbox.domain.OutboxEventTable
import io.bluetape4k.workshop.messaging.outbox.outbox.OutboxEvent
import io.bluetape4k.workshop.messaging.outbox.outbox.OutboxStatus
import jakarta.validation.Valid
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * order management 와 outbox demo endpoint 를 노출하는 REST controller 입니다.
 *
 * ## Endpoints
 * | Method | Path                          | 설명                                |
 * |--------|-------------------------------|-------------------------------------|
 * | POST   | `/api/orders`                 | 새 order 를 place 합니다.           |
 * | PUT    | `/api/orders/{id}/status`     | order status 를 update 합니다.      |
 * | GET    | `/api/orders/{id}`            | 단일 order 를 조회합니다.           |
 * | GET    | `/api/orders`                 | 모든 order 를 list 합니다.          |
 * | GET    | `/api/orders/outbox/pending`  | pending outbox event 를 list 합니다(demo). |
 */
@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val orderService: OrderService,
) {
    companion object : KLogging()

    /** 새 order 를 place 하고 생성된 [OrderResponse] 와 함께 HTTP 201 을 반환합니다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun placeOrder(@Valid @RequestBody request: OrderRequest): OrderResponse =
        orderService.placeOrder(request.customerId, request.product, request.quantity)

    /** 기존 order 를 새 status 로 transition 합니다. */
    @PutMapping("/{id}/status")
    fun updateStatus(
        @PathVariable id: Long,
        @RequestBody request: UpdateStatusRequest,
    ): OrderResponse = orderService.updateStatus(id, request.status)

    /** primary key 로 단일 order 를 조회합니다. */
    @GetMapping("/{id}")
    fun getOrder(@PathVariable id: Long): OrderResponse = orderService.getOrder(id)

    /** 모든 order 를 list 합니다. */
    @GetMapping
    fun getAllOrders(): List<OrderResponse> = orderService.getAllOrders()

    /**
     * demo endpoint 입니다. 아직 pending 또는 failed 상태인 outbox event 를 반환합니다.
     *
     * scheduler 실행 전 outbox table state 를 관찰할 때 유용합니다.
     */
    @GetMapping("/outbox/pending")
    @Transactional(readOnly = true)
    fun getPendingOutboxEvents(): List<OutboxEvent> =
        OutboxEventTable.selectAll()
            .where {
                (OutboxEventTable.status eq OutboxStatus.PENDING)
            }
            .orderBy(OutboxEventTable.createdAt to SortOrder.ASC)
            .map { row ->
                OutboxEvent(
                    id = row[OutboxEventTable.id].value,
                    aggregateType = row[OutboxEventTable.aggregateType],
                    aggregateId = row[OutboxEventTable.aggregateId],
                    eventType = row[OutboxEventTable.eventType],
                    payload = row[OutboxEventTable.payload],
                    status = row[OutboxEventTable.status],
                    retryCount = row[OutboxEventTable.retryCount],
                    createdAt = row[OutboxEventTable.createdAt],
                    processedAt = row[OutboxEventTable.processedAt],
                )
            }
}
