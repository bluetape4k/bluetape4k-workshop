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
 * REST controller exposing order management and outbox demo endpoints.
 *
 * ## Endpoints
 * | Method | Path                          | Description                         |
 * |--------|-------------------------------|-------------------------------------|
 * | POST   | `/api/orders`                 | Place a new order                   |
 * | PUT    | `/api/orders/{id}/status`     | Update an order's status            |
 * | GET    | `/api/orders/{id}`            | Get a single order                  |
 * | GET    | `/api/orders`                 | List all orders                     |
 * | GET    | `/api/orders/outbox/pending`  | List pending outbox events (demo)   |
 */
@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val orderService: OrderService,
) {
    companion object : KLogging()

    /** Place a new order; returns HTTP 201 with the created [OrderResponse]. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun placeOrder(@Valid @RequestBody request: OrderRequest): OrderResponse =
        orderService.placeOrder(request.customerId, request.product, request.quantity)

    /** Transition an existing order to a new status. */
    @PutMapping("/{id}/status")
    fun updateStatus(
        @PathVariable id: Long,
        @RequestBody request: UpdateStatusRequest,
    ): OrderResponse = orderService.updateStatus(id, request.status)

    /** Get a single order by its primary key. */
    @GetMapping("/{id}")
    fun getOrder(@PathVariable id: Long): OrderResponse = orderService.getOrder(id)

    /** List all orders. */
    @GetMapping
    fun getAllOrders(): List<OrderResponse> = orderService.getAllOrders()

    /**
     * Demo endpoint: returns outbox events that are still pending or failed.
     *
     * Useful for observing the outbox table state before the scheduler runs.
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
