package io.bluetape4k.workshop.optimization.warehouseallocation.application

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
    fun cancel(orderId: String, expectedOrderRevision: Long, requestId: String): WarehouseAllocationOrderMutationResult = transaction {
        val order = repository.findOrder(orderId)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown order")
        if (order.status == OrderStatus.CANCELLED) {
            return@transaction WarehouseAllocationOrderMutationResult(orderId, order.status, order.revision, "cancel-${orderId.take(80)}-${requestId.take(32)}", requestId)
        }
        if (order.revision != expectedOrderRevision) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "order revision changed")
        }
        if (order.status == OrderStatus.COMPLETED) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "completed order cannot be cancelled")
        }
        val lines = repository.orderLines(orderId)
        lines.filter { it.status != io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineStatus.CANCELLED && it.status != io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineStatus.FULFILLED }
            .sortedBy { it.orderLineId.value }
            .forEach { line ->
                if (!repository.cancelOrderLine(line.orderLineId.value, line.revision)) {
                    throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "order line changed")
                }
            }
        val currentLines = repository.orderLines(orderId)
        val nextStatus = repository.projectOrderStatus(currentLines)
        val nextOrder = order.copy(status = nextStatus, revision = order.revision + 1, lines = currentLines)
        if (!repository.updateOrderIfRevision(orderId, order.revision, nextOrder)) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "order changed during cancellation")
        }
        val operationKey = "cancel-${orderId.take(80)}-${requestId.take(32)}"
        repository.appendAudit(requestId, "order", orderId, "CANCELLED", "order cancellation committed")
        repository.enqueueOutbox(operationKey, "order-cancelled", "order:$orderId", nextOrder.revision, "{\"orderId\":\"${orderId.take(120)}\",\"status\":\"CANCELLED\"}")
        WarehouseAllocationOrderMutationResult(orderId, nextStatus, nextOrder.revision, operationKey, requestId)
    }
}
