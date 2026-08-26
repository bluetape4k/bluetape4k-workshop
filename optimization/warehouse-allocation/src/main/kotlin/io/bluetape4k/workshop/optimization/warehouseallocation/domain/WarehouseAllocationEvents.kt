package io.bluetape4k.workshop.optimization.warehouseallocation.domain

import java.time.Instant

internal enum class WarehouseAllocationEventType {
    INVENTORY_ADJUSTED,
    RESERVATION_REJECTED,
    ORDER_CANCELLED,
    CARRIER_CUTOFF_CHANGED,
    PICKER_CAPACITY_CHANGED,
    WAREHOUSE_INCIDENT,
}

internal data class EventTarget(
    val warehouseId: WarehouseId? = null,
    val sku: Sku? = null,
    val reservationId: ReservationId? = null,
    val orderLineId: OrderLineId? = null,
    val carrierCode: String? = null,
) {
    fun key(): String = listOfNotNull(warehouseId?.value, sku?.value, reservationId?.value, orderLineId?.value, carrierCode).joinToString("/")
}

internal sealed interface WarehouseAllocationEventPayload {
    data class InventoryAdjusted(val onHandQuantity: Int) : WarehouseAllocationEventPayload {
        init { require(onHandQuantity in 0..WarehouseAllocationLimits.MAX_QUANTITY) }
    }
    data class ReservationRejected(val reasonCode: ReservationRejectReason) : WarehouseAllocationEventPayload
    data class OrderCancelled(val lineRevision: Long) : WarehouseAllocationEventPayload {
        init { require(lineRevision >= 0) }
    }
    data class CarrierCutoffChanged(val cutoffAt: Instant) : WarehouseAllocationEventPayload
    data class PickerCapacityChanged(val capacity: Int, val effectiveAt: Instant) : WarehouseAllocationEventPayload {
        init { require(capacity in 0..WarehouseAllocationLimits.MAX_QUANTITY) }
    }
    data class WarehouseIncident(val incidentCode: String = "WAREHOUSE_INCIDENT", val active: Boolean) : WarehouseAllocationEventPayload {
        init { require(incidentCode == "WAREHOUSE_INCIDENT") }
    }
}

internal enum class ReservationRejectReason { RESERVATION_CONFLICT, CANCELLED, INCIDENT }

internal data class WarehouseAllocationEvent(
    val eventId: EventId,
    val eventKey: EventKey,
    val eventType: WarehouseAllocationEventType,
    val target: EventTarget,
    val sourceEventRevision: Long,
    val payload: WarehouseAllocationEventPayload,
    val aggregateId: String = target.key(),
) {
    init { require(sourceEventRevision >= 0) }
}

internal data class EventIngestResult(
    val state: EventState,
    val operationKey: String,
    val requestId: String,
    val error: WarehouseAllocationException? = null,
)
