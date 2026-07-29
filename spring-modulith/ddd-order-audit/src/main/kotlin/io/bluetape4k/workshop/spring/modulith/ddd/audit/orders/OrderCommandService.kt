package io.bluetape4k.workshop.spring.modulith.ddd.audit.orders

import io.bluetape4k.support.requireNotBlank
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 주문 lifecycle 작업을 담당하는 transactional command 경계입니다.
 *
 * ## 동작 / 계약
 * - 주문 상태와 Spring Modulith publication row 는 같은 transaction 안에서 등록됩니다.
 * - listener side effect 는 commit 후 `@ApplicationModuleListener` 를 통해 발생합니다.
 * - 반환되는 aggregate 에는 pending event 가 없으며, event publication 은 infrastructure 가 소유합니다.
 */
@Service
class OrderCommandService(
    private val orderRepository: OrderJpaRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val auditService: OrderAuditService,
) {

    /**
     * 새 주문을 생성하고 command transaction 안에서 [OrderPlaced] 를 publish 합니다.
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
     * 기존 주문을 승인하고 command transaction 안에서 [OrderApproved] 를 publish 합니다.
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
