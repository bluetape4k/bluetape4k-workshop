package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EffectState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReservationState
import java.time.Instant

internal data class WarehouseAllocationPlanRecord(
    val planId: String,
    val datasetId: String,
    val datasetVersion: Long,
    val planRevision: Long,
    val expectedOrderRevision: Long,
    val warehouseRevision: Long,
    val status: PlanStatus,
    val digest: String,
    val payload: String,
    val fencingToken: Long,
    val requestGeneration: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal data class WarehouseAllocationReservationRecord(
    val reservationId: String,
    val planId: String,
    val orderLineId: String,
    val warehouseId: String,
    val sku: String,
    val quantity: Int,
    val state: ReservationState,
    val revision: Long,
)

internal data class WarehouseAllocationEventRecord(
    val eventId: String,
    val eventKey: String,
    val aggregateId: String,
    val sourceRevision: Long,
    val digest: String,
    val state: EventState,
    val payloadSummary: String,
    val createdAt: Instant,
)

internal data class WarehouseAllocationIdempotencyRecord(
    val id: Long,
    val httpMethod: String,
    val routeTemplate: String,
    val demoScope: String,
    val idempotencyKey: String,
    val fingerprint: String,
    val target: String,
    val status: String,
    val operationKey: String?,
    val response: String?,
    val attempt: Int,
    val nextRetryAt: Instant?,
)

internal data class WarehouseAllocationOutboxRecord(
    val id: Long,
    val operationKey: String,
    val effectKey: String,
    val aggregateId: String,
    val aggregateRevision: Long,
    val status: OutboxState,
    val attempt: Int,
    val maxAttempts: Int,
    val nextAttemptAt: Instant,
    val leaseOwner: String?,
    val leaseToken: String?,
    val leaseExpiresAt: Instant?,
    val fencingToken: Long,
    val deliveryAttempted: Boolean,
    val payload: String,
)

internal data class WarehouseAllocationReplanRecord(
    val generation: Long,
    val datasetId: String,
    val state: String,
    val staleReason: String?,
    val planId: String?,
    val requestId: String,
    val maxRevision: Long,
)

internal data class WarehouseAllocationEffectRecord(
    val id: Long,
    val operationKey: String,
    val effectKey: String,
    val state: EffectState,
    val attempt: Int,
    val nextAttemptAt: Instant,
    val leaseOwner: String?,
    val leaseToken: String?,
    val leaseExpiresAt: Instant?,
    val fencingToken: Long,
    val deliveryAttempted: Boolean,
)
