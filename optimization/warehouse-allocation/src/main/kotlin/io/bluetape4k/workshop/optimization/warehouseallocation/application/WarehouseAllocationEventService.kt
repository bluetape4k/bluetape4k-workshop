package io.bluetape4k.workshop.optimization.warehouseallocation.application

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReservationState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEventPayload
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEventType
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEvent
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationNextAction
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationCodec
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import org.springframework.stereotype.Service
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

@Service
internal class WarehouseAllocationEventService(
    private val repository: WarehouseAllocationRepository,
    private val codec: WarehouseAllocationCodec = WarehouseAllocationCodec(),
) {
    fun ingest(event: WarehouseAllocationEvent, requestId: String): EventIngestResponse {
        validateTarget(event)
        val digest = codec.digest(event)
        val currentRevision = transaction { repository.maxEventRevision(event.aggregateId) }
        val sameRevision = transaction { repository.eventAtRevision(event.aggregateId, event.sourceEventRevision) }
        if (sameRevision != null && sameRevision.digest != digest) {
            transaction { repository.appendAudit(requestId, "event", event.aggregateId, "EVENT_REVISION_CONFLICT", "event revision digest conflict") }
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.EVENT_REVISION_CONFLICT, "event revision already has another digest")
        }
        if (currentRevision > event.sourceEventRevision) {
            transaction { repository.appendAudit(requestId, "event", event.aggregateId, "STALE_EVENT", "event revision is older than aggregate") }
            throw WarehouseAllocationException(
                WarehouseAllocationErrorCode.STALE_EVENT,
                "event revision is older than the aggregate",
                nextAction = WarehouseAllocationNextAction.NO_RETRY,
            )
        }
        val state = transaction {
            val result = repository.appendEvent(event, digest, event.payload.toString().take(240))
            if (result == EventState.ACCEPTED) {
                if (!apply(event)) throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "event target does not exist")
                repository.appendAudit(requestId, "event", event.aggregateId, "ACCEPTED", "event applied")
                repository.insertReplan("aggregate-${event.aggregateId.take(80)}", event.sourceEventRevision, requestId)
            }
            result
        }
        return EventIngestResponse("event-${event.eventId}", requestId, state)
    }

    private fun apply(event: WarehouseAllocationEvent): Boolean = when (event.eventType) {
        WarehouseAllocationEventType.INVENTORY_ADJUSTED -> {
            val payload = event.payload as WarehouseAllocationEventPayload.InventoryAdjusted
            repository.applyInventoryAdjustment(
                validated(event.target.warehouseId, "warehouseId").value,
                validated(event.target.sku, "sku").value,
                payload.onHandQuantity,
                event.sourceEventRevision,
            )
        }
        WarehouseAllocationEventType.RESERVATION_REJECTED -> {
            val lineId = validated(event.target.orderLineId, "orderLineId").value
            if (!repository.rejectReservation(validated(event.target.reservationId, "reservationId").value, lineId)) return false
            val line = repository.findOrderLine(lineId) ?: return false
            val reservations = repository.reservations(lineId)
            val nextStatus = when {
                line.status == OrderLineStatus.CANCELLED -> line.status
                reservations.any { it.state == ReservationState.ACCEPTED } -> OrderLineStatus.PARTIALLY_ALLOCATED
                else -> OrderLineStatus.OPEN
            }
            if (line.status != nextStatus && !repository.updateOrderLine(lineId, line.revision, line.copy(status = nextStatus, revision = line.revision + 1))) return false
            val orderId = line.orderId?.value ?: return false
            val order = repository.findOrder(orderId) ?: return false
            val currentLines = repository.orderLines(orderId)
            val projected = repository.projectOrderStatus(currentLines)
            order.status == projected || repository.updateOrderIfRevision(orderId, order.revision, order.copy(status = projected, revision = order.revision + 1, lines = currentLines))
        }
        WarehouseAllocationEventType.ORDER_CANCELLED -> {
            val payload = event.payload as WarehouseAllocationEventPayload.OrderCancelled
            val line = repository.findOrderLine(validated(event.target.orderLineId, "orderLineId").value) ?: return false
            if (!repository.cancelOrderLine(line.orderLineId.value, payload.lineRevision)) return false
            val orderId = line.orderId?.value ?: return false
            val order = repository.findOrder(orderId) ?: return false
            val lines = repository.orderLines(orderId)
            val status = repository.projectOrderStatus(lines)
            if (order.status == status) true else repository.updateOrderIfRevision(orderId, order.revision, order.copy(status = status, revision = order.revision + 1, lines = lines))
        }
        WarehouseAllocationEventType.CARRIER_CUTOFF_CHANGED -> repository.updateOrderLineCutoff(
            validated(event.target.orderLineId, "orderLineId").value,
            (event.payload as WarehouseAllocationEventPayload.CarrierCutoffChanged).cutoffAt,
        )
        WarehouseAllocationEventType.PICKER_CAPACITY_CHANGED -> repository.updateWarehouseCapacity(
            validated(event.target.warehouseId, "warehouseId").value,
            (event.payload as WarehouseAllocationEventPayload.PickerCapacityChanged).capacity,
        )
        WarehouseAllocationEventType.WAREHOUSE_INCIDENT -> repository.updateWarehouseIncident(
            validated(event.target.warehouseId, "warehouseId").value,
            (event.payload as WarehouseAllocationEventPayload.WarehouseIncident).active,
        )
    }

    private fun <T> validated(value: T?, field: String): T =
        checkNotNull(value) { "validated event must include $field" }

    private fun validateTarget(event: WarehouseAllocationEvent) {
        if (event.aggregateId != event.target.key()) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "aggregate target does not match event target")
        }
        val valid = when (event.eventType) {
            WarehouseAllocationEventType.INVENTORY_ADJUSTED -> event.target.warehouseId != null && event.target.sku != null && event.payload is WarehouseAllocationEventPayload.InventoryAdjusted
            WarehouseAllocationEventType.RESERVATION_REJECTED -> event.target.reservationId != null && event.target.orderLineId != null && event.payload is WarehouseAllocationEventPayload.ReservationRejected
            WarehouseAllocationEventType.ORDER_CANCELLED -> event.target.orderLineId != null && event.payload is WarehouseAllocationEventPayload.OrderCancelled
            WarehouseAllocationEventType.CARRIER_CUTOFF_CHANGED -> event.target.orderLineId != null && event.target.carrierCode != null && event.payload is WarehouseAllocationEventPayload.CarrierCutoffChanged
            WarehouseAllocationEventType.PICKER_CAPACITY_CHANGED -> event.target.warehouseId != null && event.payload is WarehouseAllocationEventPayload.PickerCapacityChanged
            WarehouseAllocationEventType.WAREHOUSE_INCIDENT -> event.target.warehouseId != null && event.payload is WarehouseAllocationEventPayload.WarehouseIncident
        }
        if (!valid) throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "event target and payload do not match")
    }
}

internal data class EventIngestResponse(val operationKey: String, val requestId: String, val state: EventState)
