package io.bluetape4k.workshop.optimization.warehouseallocation.web

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EffectState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReplanState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReplanStaleReason
import java.time.Instant

internal data class WarehouseAllocationEventRequest(
    val eventId: String,
    val eventKey: String,
    val eventType: String,
    val target: Map<String, String?> = emptyMap(),
    val sourceEventRevision: Long,
    val payload: Map<String, Any?> = emptyMap(),
)

internal data class WarehouseAllocationReplanRequest(
    val datasetId: String,
    val seed: Long = 0,
    val parentPlanRevision: Long? = null,
)

internal data class WarehouseAllocationApproveRequest(val expectedPlanRevision: Long)
internal data class WarehouseAllocationRejectRequest(val expectedPlanRevision: Long, val reasonCode: String)
internal data class WarehouseAllocationPinRequest(val lineId: String, val warehouseId: String, val quantity: Int, val expectedLineRevision: Long)
internal data class WarehouseAllocationCancelRequest(val expectedOrderRevision: Long)

internal data class WarehouseAllocationListResponse<T>(val items: List<T>, val nextCursor: String? = null)
internal data class WarehouseAllocationStockDto(val warehouseId: String, val sku: String, val availableQuantity: Int, val sourceRevision: Long)
internal data class WarehouseAllocationReservationDto(val reservationId: String, val state: String)
internal data class WarehouseAllocationOrderLineDto(
    val lineId: String,
    val status: String,
    val sku: String,
    val requestedQuantity: Int,
    val activePlanId: String? = null,
    val pinRevision: Long? = null,
    val reservations: List<WarehouseAllocationReservationDto> = emptyList(),
)
internal data class WarehouseAllocationOrderDto(val orderId: String, val status: String, val revision: Long, val lines: List<WarehouseAllocationOrderLineDto>, val nextCursor: String? = null)

internal data class WarehouseAllocationScoreDto(val hard: Int, val medium: Int, val soft: Int)
internal data class WarehouseAllocationPlanAllocationDto(
    val allocationId: String,
    val lineId: String,
    val warehouseId: String,
    val waveId: String,
    val sku: String,
    val quantity: Int,
    val pinned: Boolean,
)
internal data class WarehouseAllocationPlanReasonDto(
    val lineId: String,
    val code: String,
    val affectedQuantity: Int,
)
internal data class WarehouseAllocationPlanHistoryDto(
    val revision: Long,
    val status: PlanStatus,
    val reasonCode: String? = null,
    val createdAt: Instant? = null,
    val requestId: String? = null,
)
internal data class WarehouseAllocationPlanDto(
    val planId: String,
    val status: PlanStatus,
    val datasetVersion: Long,
    val score: WarehouseAllocationScoreDto,
    val allocations: List<WarehouseAllocationPlanAllocationDto> = emptyList(),
    val reasons: List<WarehouseAllocationPlanReasonDto> = emptyList(),
    val history: List<WarehouseAllocationPlanHistoryDto> = emptyList(),
    val nextCursor: String? = null,
)
internal data class WarehouseAllocationReplanDto(val generation: Long, val status: ReplanState, val planId: String? = null, val staleReason: ReplanStaleReason? = null, val requestId: String)
internal data class WarehouseAllocationOutboxDto(
    val operationKey: String,
    val outboxState: OutboxState,
    val effectState: EffectState? = null,
    val attempt: Int,
    val nextAttemptAt: Instant? = null,
    val reconciliationRequired: Boolean = false,
    val redriveAllowed: Boolean = false,
    val requestId: String,
)
internal data class WarehouseAllocationCommandResponse(val operationKey: String?, val requestId: String, val state: String)

internal data class PlanningRequestDto(
    val aggregateId: String,
    val aggregateVersion: Long,
    val datasetId: String,
    val parentRevision: Long? = null,
    val provider: String = "FAKE",
)
internal data class PlanningRequestResponse(val id: String, val status: String = "QUEUED")
internal data class PlanningCallbackDto(
    val eventId: String,
    val planningRequestId: String,
    val providerRevision: Long,
    val status: String,
    val scoreSummary: String,
    val constraintExplanations: List<String> = emptyList(),
)
internal data class PlanningCallbackResponse(val decision: String)
internal data class PlanningQueryResponse(
    val id: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val status: String,
    val provider: String,
    val providerRequestId: String? = null,
    val acceptedRevision: Long? = null,
    val scoreSummary: String? = null,
    val redactedExplanation: List<String> = emptyList(),
)
