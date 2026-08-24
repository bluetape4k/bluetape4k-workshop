package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EffectState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PinStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReservationState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationLimits
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PickWaveStatus

internal object WarehouseAllocationWarehousesTable : Table("warehouse_alloc_warehouses") {
    val id = long("id").autoIncrement()
    val warehouseId = varchar("warehouse_id", WarehouseAllocationLimits.MAX_IDENTIFIER).uniqueIndex()
    val payload = text("payload")
    val revision = long("revision")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationStockTable : Table("warehouse_alloc_stock") {
    val id = long("id").autoIncrement()
    val warehouseId = varchar("warehouse_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val sku = varchar("sku", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val onHandQuantity = integer("on_hand_quantity")
    val reservedQuantity = integer("reserved_quantity")
    val revision = long("revision")
    val sourceEventRevision = long("source_event_revision")
    val payload = text("payload")
    val updatedAt = timestamp("updated_at")
    init { uniqueIndex(warehouseId, sku); index(false, warehouseId, sku, revision) }
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationOrdersTable : Table("warehouse_alloc_orders") {
    val id = long("id").autoIncrement()
    val orderId = varchar("order_id", WarehouseAllocationLimits.MAX_IDENTIFIER).uniqueIndex()
    val status = enumerationByName<OrderStatus>("status", 32)
    val revision = long("revision")
    val payload = text("payload")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationOrderLinesTable : Table("warehouse_alloc_order_lines") {
    val id = long("id").autoIncrement()
    val orderLineId = varchar("order_line_id", WarehouseAllocationLimits.MAX_IDENTIFIER).uniqueIndex()
    val orderId = varchar("order_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val sku = varchar("sku", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val requestedQuantity = integer("requested_quantity")
    val status = enumerationByName<OrderLineStatus>("status", 32)
    val revision = long("revision")
    val activePlanId = varchar("active_plan_id", WarehouseAllocationLimits.MAX_IDENTIFIER).nullable()
    val payload = text("payload")
    val updatedAt = timestamp("updated_at")
    init { index(false, orderId, orderLineId); index(false, activePlanId) }
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationWavesTable : Table("warehouse_alloc_pick_waves") {
    val id = long("id").autoIncrement()
    val waveId = varchar("wave_id", WarehouseAllocationLimits.MAX_IDENTIFIER).uniqueIndex()
    val warehouseId = varchar("warehouse_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val status = enumerationByName<PickWaveStatus>("status", 24)
    val revision = long("revision")
    val payload = text("payload")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationPinsTable : Table("warehouse_alloc_pins") {
    val id = long("id").autoIncrement()
    val pinId = varchar("pin_id", WarehouseAllocationLimits.MAX_IDENTIFIER).uniqueIndex()
    val orderLineId = varchar("order_line_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val warehouseId = varchar("warehouse_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val quantity = integer("quantity")
    val revision = long("revision")
    val status = enumerationByName<PinStatus>("status", 24)
    val createdBy = varchar("created_by", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val payload = text("payload")
    val updatedAt = timestamp("updated_at")
    init { index(false, orderLineId, status) }
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationPlansTable : Table("warehouse_alloc_plans") {
    val id = long("id").autoIncrement()
    val planId = varchar("plan_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val datasetId = varchar("dataset_id", WarehouseAllocationLimits.MAX_DATASET_ID)
    val datasetVersion = long("dataset_version")
    val planRevision = long("plan_revision")
    val expectedOrderRevision = long("expected_order_revision")
    val warehouseRevision = long("warehouse_revision")
    val status = enumerationByName<PlanStatus>("status", 24)
    val payload = text("payload")
    val digest = varchar("digest", 64)
    val fencingToken = long("fencing_token")
    val requestGeneration = long("request_generation")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    init { uniqueIndex(planId, planRevision); index(false, datasetId, datasetVersion); index(false, status, planRevision) }
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationAllocationsTable : Table("warehouse_alloc_allocations") {
    val id = long("id").autoIncrement()
    val planId = varchar("plan_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val orderLineId = varchar("order_line_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val warehouseId = varchar("warehouse_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val waveId = varchar("wave_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val quantity = integer("quantity")
    init { uniqueIndex(planId, orderLineId, warehouseId, waveId) }
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationReservationsTable : Table("warehouse_alloc_reservations") {
    val id = long("id").autoIncrement()
    val reservationId = varchar("reservation_id", WarehouseAllocationLimits.MAX_IDENTIFIER).uniqueIndex()
    val planId = varchar("plan_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val orderLineId = varchar("order_line_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val warehouseId = varchar("warehouse_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val sku = varchar("sku", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val quantity = integer("quantity")
    val state = enumerationByName<ReservationState>("state", 24)
    val revision = long("revision")
    val updatedAt = timestamp("updated_at")
    init { uniqueIndex(planId, orderLineId, warehouseId); index(false, orderLineId, state) }
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationEventInboxTable : Table("warehouse_alloc_event_inbox") {
    val id = long("id").autoIncrement()
    val eventId = varchar("event_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val eventKey = varchar("event_key", WarehouseAllocationLimits.MAX_EVENT_KEY)
    val aggregateId = varchar("aggregate_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val sourceRevision = long("source_revision")
    val digest = varchar("digest", 64)
    val state = enumerationByName<EventState>("state", 24)
    val payloadSummary = varchar("payload_summary", 240)
    val createdAt = timestamp("created_at")
    init { uniqueIndex(eventId); uniqueIndex(aggregateId, eventKey); index(false, aggregateId, sourceRevision) }
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationIdempotencyTable : Table("warehouse_alloc_idempotency") {
    val id = long("id").autoIncrement()
    val httpMethod = varchar("http_method", 16)
    val routeTemplate = varchar("route_template", 160)
    val demoScope = varchar("demo_scope", 64)
    val idempotencyKey = varchar("idempotency_key", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val fingerprint = varchar("fingerprint", 64)
    val target = varchar("target", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val status = varchar("status", 32)
    val operationKey = varchar("operation_key", WarehouseAllocationLimits.MAX_IDENTIFIER).nullable()
    val response = text("response").nullable()
    val attempt = integer("attempt")
    val nextRetryAt = timestamp("next_retry_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    init { uniqueIndex(httpMethod, routeTemplate, demoScope, idempotencyKey); index(false, status, nextRetryAt, id) }
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationOutboxTable : Table("warehouse_alloc_outbox") {
    val id = long("id").autoIncrement()
    val operationKey = varchar("operation_key", WarehouseAllocationLimits.MAX_IDENTIFIER).uniqueIndex()
    val effectKey = varchar("effect_key", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val aggregateId = varchar("aggregate_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val aggregateRevision = long("aggregate_revision")
    val status = enumerationByName<OutboxState>("status", 32)
    val attempt = integer("attempt")
    val maxAttempts = integer("max_attempts")
    val nextAttemptAt = timestamp("next_attempt_at")
    val leaseOwner = varchar("lease_owner", 120).nullable()
    val leaseToken = varchar("lease_token", 120).nullable()
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val fencingToken = long("fencing_token")
    val deliveryAttempted = bool("delivery_attempted")
    val payload = text("payload")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    init { index(false, status, nextAttemptAt, id) }
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationOutboxEffectsTable : Table("warehouse_alloc_outbox_effects") {
    val id = long("id").autoIncrement()
    val operationKey = varchar("operation_key", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val effectKey = varchar("effect_key", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val state = enumerationByName<EffectState>("state", 32)
    val attempt = integer("attempt")
    val nextAttemptAt = timestamp("next_attempt_at")
    val leaseOwner = varchar("lease_owner", 120).nullable()
    val leaseToken = varchar("lease_token", 120).nullable()
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val fencingToken = long("fencing_token")
    val deliveryAttempted = bool("delivery_attempted")
    val updatedAt = timestamp("updated_at")
    init { uniqueIndex(operationKey, effectKey); index(false, state, nextAttemptAt, id) }
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationAuditsTable : Table("warehouse_alloc_audits") {
    val id = long("id").autoIncrement()
    val requestId = varchar("request_id", 128)
    val aggregateType = varchar("aggregate_type", 64)
    val aggregateId = varchar("aggregate_id", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val decision = varchar("decision", 64)
    val summary = varchar("summary", 240)
    val createdBy = varchar("created_by", WarehouseAllocationLimits.MAX_IDENTIFIER)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationReplansTable : Table("warehouse_alloc_replans") {
    val id = long("id").autoIncrement()
    val generation = long("generation")
    val datasetId = varchar("dataset_id", WarehouseAllocationLimits.MAX_DATASET_ID)
    val state = varchar("state", 32)
    val staleReason = varchar("stale_reason", 64).nullable()
    val planId = varchar("plan_id", WarehouseAllocationLimits.MAX_IDENTIFIER).nullable()
    val requestId = varchar("request_id", 128)
    val maxRevision = long("max_revision")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    init { uniqueIndex(datasetId, generation); index(false, datasetId, generation) }
    override val primaryKey = PrimaryKey(id)
}

internal object WarehouseAllocationTables {
    val all = arrayOf(
        WarehouseAllocationWarehousesTable,
        WarehouseAllocationStockTable,
        WarehouseAllocationOrdersTable,
        WarehouseAllocationOrderLinesTable,
        WarehouseAllocationWavesTable,
        WarehouseAllocationPinsTable,
        WarehouseAllocationPlansTable,
        WarehouseAllocationAllocationsTable,
        WarehouseAllocationReservationsTable,
        WarehouseAllocationEventInboxTable,
        WarehouseAllocationIdempotencyTable,
        WarehouseAllocationOutboxTable,
        WarehouseAllocationOutboxEffectsTable,
        WarehouseAllocationAuditsTable,
        WarehouseAllocationReplansTable,
    )
}
