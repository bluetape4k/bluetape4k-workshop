package io.bluetape4k.workshop.optimization.warehouseallocation.domain

private fun String.requireIdentifier(name: String, max: Int): String {
    require(isNotBlank()) { "$name must not be blank" }
    require(length <= max) { "$name must be <= $max characters" }
    require(all { !it.isISOControl() }) { "$name contains a control character" }
    return this
}

@JvmInline value class DatasetId(val value: String) {
    init { value.requireIdentifier("datasetId", WarehouseAllocationLimits.MAX_DATASET_ID) }
    override fun toString(): String = value
}

@JvmInline value class WarehouseId(val value: String) {
    init { value.requireIdentifier("warehouseId", WarehouseAllocationLimits.MAX_IDENTIFIER) }
    override fun toString(): String = value
}

@JvmInline value class Sku(val value: String) {
    init { value.requireIdentifier("sku", WarehouseAllocationLimits.MAX_IDENTIFIER) }
    override fun toString(): String = value
}

@JvmInline value class OrderId(val value: String) {
    init { value.requireIdentifier("orderId", WarehouseAllocationLimits.MAX_IDENTIFIER) }
    override fun toString(): String = value
}

@JvmInline value class OrderLineId(val value: String) {
    init { value.requireIdentifier("orderLineId", WarehouseAllocationLimits.MAX_IDENTIFIER) }
    override fun toString(): String = value
}

@JvmInline value class WaveId(val value: String) {
    init { value.requireIdentifier("waveId", WarehouseAllocationLimits.MAX_IDENTIFIER) }
    override fun toString(): String = value
}

@JvmInline value class PlanId(val value: String) {
    init { value.requireIdentifier("planId", WarehouseAllocationLimits.MAX_IDENTIFIER) }
    override fun toString(): String = value
}

@JvmInline value class ReservationId(val value: String) {
    init { value.requireIdentifier("reservationId", WarehouseAllocationLimits.MAX_IDENTIFIER) }
    override fun toString(): String = value
}

@JvmInline value class PinId(val value: String) {
    init { value.requireIdentifier("pinId", WarehouseAllocationLimits.MAX_IDENTIFIER) }
    override fun toString(): String = value
}

@JvmInline value class EventId(val value: String) {
    init { value.requireIdentifier("eventId", WarehouseAllocationLimits.MAX_IDENTIFIER) }
    override fun toString(): String = value
}

@JvmInline value class EventKey(val value: String) {
    init { value.requireIdentifier("eventKey", WarehouseAllocationLimits.MAX_EVENT_KEY) }
    override fun toString(): String = value
}

@JvmInline value class IdempotencyKey(val value: String) {
    init { value.requireIdentifier("idempotencyKey", WarehouseAllocationLimits.MAX_IDENTIFIER) }
    override fun toString(): String = value
}

enum class WarehouseCapability { COLD_CHAIN, HAZMAT }

enum class ShippingRule { STANDARD, COLD_CHAIN, HAZMAT, COLD_CHAIN_AND_HAZMAT }

enum class OrderStatus { OPEN, PARTIALLY_ALLOCATED, CANCELLED, COMPLETED }

enum class OrderLineStatus { OPEN, ALLOCATED, PARTIALLY_ALLOCATED, CANCELLED, FULFILLED }

enum class PickWaveStatus { OPEN, CLOSED, CANCELLED }

enum class PinStatus { ACTIVE, REMOVED, STALE }

enum class PlanStatus { DRAFT, STALE, APPROVED, REJECTED, CANCELLED }

enum class ReplanState { QUEUED, RUNNING, SUCCEEDED, FAILED, STALE }

enum class ReplanStaleReason {
    STALE_SOLVER_RESULT,
    ORDER_REVISION_CHANGED,
    STOCK_REVISION_CHANGED,
    WAVE_REVISION_CHANGED,
    PIN_REVISION_CHANGED,
    WAREHOUSE_REVISION_CHANGED,
    CARRIER_CUTOFF_CHANGED,
    ORDER_CANCELLED,
}

enum class ReservationState { PENDING, ACCEPTED, REJECTED, RELEASED, CANCELLED }

enum class EventState { ACCEPTED, DUPLICATE }

enum class OutboxState { PENDING, CLAIMED, DELIVERY_UNKNOWN, DELIVERED, RETRYABLE, DEAD_LETTER }

enum class EffectState { CLAIMED, COMPLETED, RETRYABLE, RECONCILE_REQUIRED, DEAD_LETTER }

enum class WarehouseAllocationReasonCode {
    STOCK_UNAVAILABLE,
    COLD_CHAIN,
    HAZMAT,
    CARRIER_CUTOFF,
    PICKER_CAPACITY,
    WAREHOUSE_INCIDENT,
    PIN_CONFLICT,
    PIN_STALE,
    SPLIT_SHIPMENT,
}
