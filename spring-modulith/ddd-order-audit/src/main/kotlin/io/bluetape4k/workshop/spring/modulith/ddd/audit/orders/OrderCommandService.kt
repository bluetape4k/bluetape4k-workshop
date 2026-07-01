package io.bluetape4k.workshop.spring.modulith.ddd.audit.orders

import io.bluetape4k.support.requireNotBlank
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Transactional command boundary for order lifecycle operations.
 *
 * ## Behavior / Contract
 * - Order state and Spring Modulith publication rows are registered in the same transaction.
 * - Listener side effects happen after commit through `@ApplicationModuleListener`.
 * - Returned aggregates have no pending events; infrastructure owns event publication.
 */
@Service
class OrderCommandService(
    private val orderRepository: OrderJpaRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val auditService: OrderAuditService,
) {

    /**
     * Places a new order and publishes [OrderPlaced] inside the command transaction.
     */
    @Transactional
    fun place(command: PlaceOrderCommand): Order {
        val order = Order.place(command)
        orderRepository.save(OrderEntity.from(order))
        val auditedOrder = order.withoutEvents()
        auditService.commitAfterTransaction(
            author = "order-command",
            order = auditedOrder,
            properties = mapOf("action" to "place"),
        )
        order.events.forEach(eventPublisher::publishEvent)
        return auditedOrder
    }

    /**
     * Approves an existing order and publishes [OrderApproved] inside the command transaction.
     */
    @Transactional
    fun approve(command: ApproveOrderCommand): Order {
        val current = orderRepository.findByIdOrNull(command.orderId.value)
            ?: throw IllegalArgumentException("Order was not found. orderId=${command.orderId.value}")

        command.orderId.value.requireNotBlank("orderId")

        val approved = current.toDomain().approve(command)
        orderRepository.save(current.apply(approved))
        val auditedOrder = approved.withoutEvents()
        auditService.commitAfterTransaction(
            author = command.approvedBy,
            order = auditedOrder,
            properties = mapOf("action" to "approve"),
        )
        approved.events.forEach(eventPublisher::publishEvent)
        return auditedOrder
    }
}
