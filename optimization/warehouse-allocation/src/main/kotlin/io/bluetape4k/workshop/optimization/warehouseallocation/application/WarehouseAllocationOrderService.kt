package io.bluetape4k.workshop.optimization.warehouseallocation.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service

internal data class WarehouseAllocationOrderMutationResult(
    val orderId: String,
    val status: OrderStatus,
    val revision: Long,
    val operationKey: String,
    val requestId: String,
)

@Service
internal class WarehouseAllocationOrderService(
    private val repository: WarehouseAllocationRepository,
) {

    companion object: KLogging()

    fun cancel(
        orderId: String,
        expectedOrderRevision: Long,
        requestId: String,
    ): WarehouseAllocationOrderMutationResult = transaction {
        val order = repository.findOrder(orderId)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown order")

        log.debug { "order=$order" }
        if (order.status == OrderStatus.CANCELLED) {
            return@transaction WarehouseAllocationOrderMutationResult(
                orderId,
                order.status,
                order.revision,
                "cancel-${orderId.take(80)}-${requestId.take(32)}",
                requestId
            )
        }
        if (order.revision != expectedOrderRevision) {
            throw WarehouseAllocationException(
                WarehouseAllocationErrorCode.RESERVATION_CONFLICT,
                "order revision changed"
            )
        }
        if (order.status == OrderStatus.COMPLETED) {
            throw WarehouseAllocationException(
                WarehouseAllocationErrorCode.RESERVATION_CONFLICT,
                "completed order cannot be cancelled"
            )
        }
        val lines = repository.orderLines(orderId)

        log.debug { "lines=$lines" }
        lines
            .filter { it.status != OrderLineStatus.CANCELLED && it.status != OrderLineStatus.FULFILLED }
            .sortedBy { it.orderLineId.value }
            .forEach { line ->
                if (!repository.cancelOrderLine(line.orderLineId.value, line.revision)) {
                    throw WarehouseAllocationException(
                        WarehouseAllocationErrorCode.RESERVATION_CONFLICT,
                        "order line changed"
                    )
                }
            }
        val currentLines = repository.orderLines(orderId)
        val nextStatus = repository.projectOrderStatus(currentLines)
        val nextOrder = order.copy(status = nextStatus, revision = order.revision + 1, lines = currentLines)

        if (!repository.updateOrderIfRevision(orderId, order.revision, nextOrder)) {
            throw WarehouseAllocationException(
                WarehouseAllocationErrorCode.RESERVATION_CONFLICT,
                "order changed during cancellation"
            )
        }
        val operationKey = "cancel-${orderId.take(80)}-${requestId.take(32)}"

        repository.appendAudit(
            requestId = requestId,
            aggregateType = "order",
            aggregateId = orderId,
            decision = "CANCELLED",
            summary = "order cancellation committed"
        )
        repository.enqueueOutbox(
            operationKey = operationKey,
            effectKey = "order-cancelled",
            aggregateId = "order:$orderId",
            aggregateRevision = nextOrder.revision,
            payload = "{\"orderId\":\"${orderId.take(120)}\",\"status\":\"CANCELLED\"}"
        )

        WarehouseAllocationOrderMutationResult(
            orderId = orderId,
            status = nextStatus,
            revision = nextOrder.revision,
            operationKey = operationKey,
            requestId = requestId
        )
    }
}
