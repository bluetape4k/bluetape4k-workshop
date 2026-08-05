package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.order.domain.AggregateType
import io.bluetape4k.workshop.commerce.order.domain.CancellationPolicy
import io.bluetape4k.workshop.commerce.order.domain.CancellationStatus
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentPolicy
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentStatus
import io.bluetape4k.workshop.commerce.order.domain.OrderPolicy
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.OrderSubmitted
import io.bluetape4k.workshop.commerce.order.domain.RefundRequested
import io.bluetape4k.workshop.commerce.order.domain.RefundStatus
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrder
import io.bluetape4k.workshop.commerce.order.persistence.CancellationCaseRecord
import io.bluetape4k.workshop.commerce.order.persistence.CancellationCaseRepository
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentGroupRepository
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.InventoryReservationRecord
import io.bluetape4k.workshop.commerce.order.persistence.InventoryReservationRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderLineRecord
import io.bluetape4k.workshop.commerce.order.persistence.OrderLineRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderRecord
import io.bluetape4k.workshop.commerce.order.persistence.OrderRepository
import io.bluetape4k.workshop.commerce.order.persistence.PaymentAttemptRecord
import io.bluetape4k.workshop.commerce.order.persistence.PaymentAttemptRepository
import io.bluetape4k.workshop.commerce.order.persistence.RefundCaseRecord
import io.bluetape4k.workshop.commerce.order.persistence.RefundCaseRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class SubmittedOrderIds(
    val orderId: UUID,
    val paymentAttemptId: UUID,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class LineCancellationResult(
    val cancellationCaseId: UUID,
    val refundCaseId: UUID,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Service
internal class OrderCommandService(
    private val orders: OrderRepository,
    private val payments: PaymentAttemptRepository,
    private val lines: OrderLineRepository,
    private val reservations: InventoryReservationRepository,
    private val fulfillments: FulfillmentGroupRepository,
    private val fulfillmentLines: FulfillmentLineRepository,
    private val cancellations: CancellationCaseRepository,
    private val refunds: RefundCaseRepository,
    private val audit: LifecycleAuditAppender,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun submit(command: SubmitOrder): SubmittedOrderIds {
        require(command.tenantId.matches(IDENTIFIER)) { "invalid tenantId" }
        require(command.customerReference.matches(IDENTIFIER)) { "invalid customerReference" }
        command.lines.requireNotEmpty("lines")
        command.lines.size.requireInRange(1, 50, "lines.size")
        command.lines.forEach {
            require(it.sku.matches(IDENTIFIER))
            it.quantity.requireInRange(1, 1_000, "quantity")
            require(it.unitPrice.signum() >= 0)
        }

        val orderId = Uuid.V7.nextId()
        val paymentId = Uuid.V7.nextId()
        val reservationId = Uuid.V7.nextId()
        orders.save(
            OrderRecord(
                id = orderId,
                tenantId = command.tenantId,
                customerReference = command.customerReference,
                status = OrderStatus.SUBMITTED,
                providerMode = command.providerMode
            )
        )
        payments.save(
            PaymentAttemptRecord(paymentId, orderId, io.bluetape4k.workshop.commerce.order.domain.PaymentStatus.CREATED)
        )
        reservations.save(
            InventoryReservationRecord(
                reservationId,
                orderId,
                io.bluetape4k.workshop.commerce.order.domain.ReservationStatus.HELD
            )
        )
        command.lines.forEach { line ->
            lines.save(
                OrderLineRecord(
                    lineId = Uuid.V7.nextId(),
                    orderId = orderId,
                    sku = line.sku,
                    quantity = line.quantity,
                    unitPrice = line.unitPrice
                )
            )
        }
        audit.append(orderId, AggregateType.ORDER, orderId, 0, null, OrderStatus.SUBMITTED, actor = "CUSTOMER")
        audit.append(
            orderId,
            AggregateType.PAYMENT_ATTEMPT,
            paymentId,
            0,
            null,
            io.bluetape4k.workshop.commerce.order.domain.PaymentStatus.CREATED,
            actor = "SYSTEM"
        )
        audit.append(
            orderId,
            AggregateType.INVENTORY_RESERVATION,
            reservationId,
            0,
            null,
            io.bluetape4k.workshop.commerce.order.domain.ReservationStatus.HELD,
            actor = "SYSTEM"
        )
        events.publishEvent(
            OrderSubmitted(Uuid.V7.nextId(), orderId, paymentId, command.providerMode, Instant.now(clock))
        )
        log.info {
            "order_submitted orderId=$orderId paymentAttemptId=$paymentId providerMode=${command.providerMode} " +
                "lineCount=${command.lines.size}"
        }
        return SubmittedOrderIds(orderId, paymentId)
    }

    @Transactional
    fun advanceFulfillment(
        groupId: UUID,
        target: FulfillmentStatus,
    ): Boolean {
        val group = fulfillments.findById(groupId)
        FulfillmentPolicy.transition(group.status, target)
        val updated = fulfillments.transition(group.id, group.revision, group.status, target)
        if (updated) {
            audit.append(
                group.orderId,
                AggregateType.FULFILLMENT_GROUP,
                group.id,
                group.revision + 1,
                group.status,
                target,
                actor = "OPERATOR"
            )
            finishOrderWhenFulfillmentTerminal(group.orderId)
            log.info {
                "fulfillment_transition orderId=${group.orderId} groupId=$groupId from=${group.status} to=$target " +
                    "revision=${group.revision + 1}"
            }
        } else {
            log.warn {
                "fulfillment_transition_conflict orderId=${group.orderId} groupId=$groupId " +
                    "expectedRevision=${group.revision} from=${group.status} to=$target"
            }
        }
        return updated
    }

    @Transactional
    fun cancelUnshippedLine(
        orderId: UUID,
        lineId: UUID,
        quantity: Int,
        reasonCode: String,
    ): LineCancellationResult {
        require(reasonCode.matches(REASON)) { "invalid reasonCode" }
        quantity.requirePositiveNumber("quantity")
        val line = lines.findByLineId(lineId)
        check(line != null && line.orderId == orderId) { "order line not found" }
        val linkedLines = fulfillmentLines.findByLineId(lineId)
        val linkedGroups = linkedLines.map { fulfillments.findById(it.fulfillmentGroupId) }
        val cancellableGroupIds =
            linkedGroups
                .filter { it.status !in NON_CANCELLABLE_FULFILLMENT_STATUSES }
                .mapTo(linkedSetOf()) { it.id }
        val cancellableQuantity =
            linkedLines.filter { it.fulfillmentGroupId in cancellableGroupIds }.sumOf { it.quantity }
        check(cancellableQuantity >= quantity) { "requested quantity exceeds unshipped quantity" }
        val cancellationId = Uuid.V7.nextId()
        cancellations.save(
            CancellationCaseRecord(
                id = cancellationId,
                orderId = orderId,
                lineId = lineId,
                quantity = quantity,
                status = CancellationStatus.REQUESTED,
                reasonCode = reasonCode
            )
        )
        audit.append(
            orderId,
            AggregateType.CANCELLATION_CASE,
            cancellationId,
            0,
            null,
            CancellationStatus.REQUESTED,
            reasonCode,
            "OPERATOR"
        )
        check(lines.cancel(lineId, quantity)) { "line cancellation conflict" }
        check(fulfillmentLines.cancel(lineId, cancellableGroupIds, quantity)) {
            "fulfillment line cancellation conflict"
        }
        CancellationPolicy.transition(CancellationStatus.REQUESTED, CancellationStatus.APPROVED)
        check(
            cancellations.transition(
                cancellationId,
                0,
                CancellationStatus.REQUESTED,
                CancellationStatus.APPROVED
            )
        ) { "cancellation approval conflict" }
        audit.append(
            orderId,
            AggregateType.CANCELLATION_CASE,
            cancellationId,
            1,
            CancellationStatus.REQUESTED,
            CancellationStatus.APPROVED,
            reasonCode,
            "SYSTEM"
        )
        cancelEmptyFulfillmentGroups(orderId, linkedGroups.filter { it.id in cancellableGroupIds })
        finishOrderWhenFulfillmentTerminal(orderId)
        val refundId = Uuid.V7.nextId()
        refunds.save(RefundCaseRecord(refundId, orderId, RefundStatus.REQUESTED, reasonCode = reasonCode))
        audit.append(
            orderId,
            AggregateType.REFUND_CASE,
            refundId,
            0,
            null,
            RefundStatus.REQUESTED,
            reasonCode,
            "OPERATOR"
        )
        events.publishEvent(RefundRequested(Uuid.V7.nextId(), orderId, refundId, reasonCode, Instant.now(clock)))
        log.info {
            "line_cancellation_approved orderId=$orderId lineId=$lineId quantity=$quantity " +
                "cancellationCaseId=$cancellationId refundCaseId=$refundId reasonCode=$reasonCode"
        }
        return LineCancellationResult(cancellationId, refundId)
    }

    private fun cancelEmptyFulfillmentGroups(
        orderId: UUID,
        linkedGroups: List<io.bluetape4k.workshop.commerce.order.persistence.FulfillmentGroupRecord>,
    ) {
        val remainingByGroup = fulfillmentLines.findByOrderId(orderId).groupBy { it.fulfillmentGroupId }
        linkedGroups.forEach { group ->
            if (remainingByGroup[group.id].orEmpty().all { it.quantity == 0 }) {
                FulfillmentPolicy.transition(group.status, FulfillmentStatus.CANCELLED)
                if (fulfillments.transition(
                        group.id,
                        group.revision,
                        group.status,
                        FulfillmentStatus.CANCELLED,
                        "LINE_CANCELLED"
                    )
                ) {
                    audit.append(
                        orderId,
                        AggregateType.FULFILLMENT_GROUP,
                        group.id,
                        group.revision + 1,
                        group.status,
                        FulfillmentStatus.CANCELLED,
                        "LINE_CANCELLED",
                        "SYSTEM"
                    )
                }
            }
        }
    }

    private fun finishOrderWhenFulfillmentTerminal(orderId: UUID) {
        val groups = fulfillments.findByOrderId(orderId)
        if (groups.isNotEmpty() && groups.all { it.status in ORDER_TERMINAL_FULFILLMENT_STATUSES }) {
            val order = orders.findById(orderId)
            val target =
                if (groups.all { it.status == FulfillmentStatus.CANCELLED }) {
                    OrderStatus.CANCELLED
                } else {
                    OrderStatus.COMPLETED
                }
            OrderPolicy.transition(order.status, target)
            if (orders.transition(order.id, order.revision, order.status, target)) {
                audit.append(
                    orderId,
                    AggregateType.ORDER,
                    orderId,
                    order.revision + 1,
                    order.status,
                    target,
                    actor = "SYSTEM"
                )
                log.info {
                    "order_terminal orderId=$orderId status=$target revision=${order.revision + 1} " +
                        "deliveredGroups=${groups.count { it.status == FulfillmentStatus.DELIVERED }} " +
                        "cancelledGroups=${groups.count { it.status == FulfillmentStatus.CANCELLED }}"
                }
            }
        }
    }

    companion object : KLogging() {
        private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,120}")
        private val REASON = Regex("[A-Z0-9_]{1,80}")
        private val NON_CANCELLABLE_FULFILLMENT_STATUSES =
            setOf(FulfillmentStatus.SHIPPED, FulfillmentStatus.DELIVERED, FulfillmentStatus.CANCELLED)
        private val ORDER_TERMINAL_FULFILLMENT_STATUSES =
            setOf(FulfillmentStatus.DELIVERED, FulfillmentStatus.CANCELLED)
    }
}
