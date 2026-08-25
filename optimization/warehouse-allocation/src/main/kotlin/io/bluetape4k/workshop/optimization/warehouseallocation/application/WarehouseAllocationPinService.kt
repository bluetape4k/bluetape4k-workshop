package io.bluetape4k.workshop.optimization.warehouseallocation.application

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.CommittedAllocationPin
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PinId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PinStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineId
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service

internal data class WarehouseAllocationPinMutationResult(
    val pinId: String,
    val lineId: String,
    val revision: Long,
    val status: PinStatus,
    val requestId: String,
)

@Service
internal class WarehouseAllocationPinService(
    private val repository: WarehouseAllocationRepository,
) {
    fun create(lineId: String, warehouseId: String, quantity: Int, expectedLineRevision: Long, actor: String, requestId: String): WarehouseAllocationPinMutationResult = transaction {
        val pinId = PinId("pin-${requestId.take(120)}")
        repository.findPin(pinId.value)?.let { existing ->
            if (existing.orderLineId.value != lineId || existing.warehouseId.value != warehouseId || existing.quantity != quantity) {
                throw WarehouseAllocationException(WarehouseAllocationErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT, "pin request was reused with different target")
            }
            return@transaction WarehouseAllocationPinMutationResult(existing.pinId.value, existing.orderLineId.value, existing.pinRevision, existing.status, requestId)
        }
        val line = repository.findOrderLine(lineId)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown order line")
        if (line.revision != expectedLineRevision || line.status !in setOf(OrderLineStatus.OPEN, OrderLineStatus.PARTIALLY_ALLOCATED)) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "order line revision or status changed")
        }
        if (repository.findWarehouse(warehouseId) == null) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown warehouse")
        }
        if (repository.activePin(lineId) != null) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "order line already has an active pin")
        }
        val pin = CommittedAllocationPin(pinId, OrderLineId(lineId), WarehouseId(warehouseId), quantity, expectedLineRevision + 1, actor.take(120), PinStatus.ACTIVE)
        repository.savePin(pin)
        val next = line.copy(revision = expectedLineRevision + 1)
        if (!repository.updateOrderLine(lineId, expectedLineRevision, next)) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "order line changed while pinning")
        }
        repository.appendAudit(requestId, "order_line", lineId, "PIN_CREATED", "manual allocation pin created")
        WarehouseAllocationPinMutationResult(pinId.value, lineId, pin.pinRevision, pin.status, requestId)
    }

    fun remove(pinId: String, expectedRevision: Long, requestId: String): WarehouseAllocationPinMutationResult = transaction {
        val pin = repository.findPin(pinId)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown pin")
        if (pin.status == PinStatus.REMOVED) {
            return@transaction WarehouseAllocationPinMutationResult(pinId, pin.orderLineId.value, pin.pinRevision, pin.status, requestId)
        }
        if (pin.pinRevision != expectedRevision) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "pin revision changed")
        }
        val line = repository.findOrderLine(pin.orderLineId.value)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown order line")
        if (!repository.updatePin(pinId, expectedRevision, pin.copy(pinRevision = expectedRevision + 1, status = PinStatus.REMOVED))) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "pin changed while removing")
        }
        if (!repository.updateOrderLine(line.orderLineId.value, line.revision, line.copy(revision = line.revision + 1))) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "order line changed while removing pin")
        }
        repository.appendAudit(requestId, "pin", pinId, "PIN_REMOVED", "manual allocation pin removed")
        WarehouseAllocationPinMutationResult(pinId, pin.orderLineId.value, expectedRevision + 1, PinStatus.REMOVED, requestId)
    }
}
