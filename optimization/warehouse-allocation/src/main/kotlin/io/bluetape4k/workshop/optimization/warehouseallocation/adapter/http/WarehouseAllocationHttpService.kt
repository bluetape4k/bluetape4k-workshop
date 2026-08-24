package io.bluetape4k.workshop.optimization.warehouseallocation.adapter.http

import io.bluetape4k.workshop.optimization.warehouseallocation.application.EventIngestResponse
import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationEventService
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventKey
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.EventTarget
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Sku
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEvent
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEventPayload
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationEventType
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReservationId
import io.bluetape4k.workshop.optimization.warehouseallocation.web.PlanningCallbackDto
import io.bluetape4k.workshop.optimization.warehouseallocation.web.PlanningCallbackResponse
import io.bluetape4k.workshop.optimization.warehouseallocation.web.PlanningQueryResponse
import io.bluetape4k.workshop.optimization.warehouseallocation.web.PlanningRequestDto
import io.bluetape4k.workshop.optimization.warehouseallocation.web.PlanningRequestResponse
import io.bluetape4k.workshop.optimization.warehouseallocation.web.WarehouseAllocationEventRequest
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
internal class WarehouseAllocationHttpService(
    private val eventService: WarehouseAllocationEventService,
) {
    private val planning = ConcurrentHashMap<String, WarehouseAllocationPlanningRequestState>()

    fun ingest(request: WarehouseAllocationEventRequest, requestId: String): EventIngestResponse =
        eventService.ingest(toEvent(request), requestId)

    fun createPlanningRequest(request: PlanningRequestDto): PlanningRequestResponse {
        val id = UUID.randomUUID().toString()
        planning[id] = WarehouseAllocationPlanningRequestState(id, request.aggregateId, request.aggregateVersion, request.datasetId, request.provider)
        return PlanningRequestResponse(id)
    }

    fun callback(provider: String, request: PlanningCallbackDto): PlanningCallbackResponse {
        val state = planning[request.planningRequestId] ?: return PlanningCallbackResponse("REJECTED")
        if (!state.provider.equals(provider, ignoreCase = true)) return PlanningCallbackResponse("PROVIDER_MISMATCH")
        if (state.providerRevision != null && request.providerRevision < state.providerRevision!!) return PlanningCallbackResponse("STALE_REVISION")
        if (state.providerRevision == request.providerRevision && state.status == request.status) return PlanningCallbackResponse("DUPLICATE")
        state.providerRevision = request.providerRevision
        state.status = request.status
        state.scoreSummary = request.scoreSummary
        state.explanations = request.constraintExplanations.take(20).map { it.take(240) }
        return PlanningCallbackResponse("ACCEPTED")
    }

    fun queryPlanningRequest(id: String): PlanningQueryResponse? = planning[id]?.let {
        PlanningQueryResponse(it.id, it.aggregateId, it.aggregateVersion, it.status, it.provider, null, it.providerRevision, it.scoreSummary, it.explanations)
    }

    private fun toEvent(request: WarehouseAllocationEventRequest): WarehouseAllocationEvent = try {
        val type = WarehouseAllocationEventType.valueOf(request.eventType.uppercase())
        val targetKeys = when (type) {
            WarehouseAllocationEventType.INVENTORY_ADJUSTED -> setOf("warehouseId", "sku")
            WarehouseAllocationEventType.RESERVATION_REJECTED -> setOf("reservationId", "orderLineId")
            WarehouseAllocationEventType.ORDER_CANCELLED -> setOf("orderLineId")
            WarehouseAllocationEventType.CARRIER_CUTOFF_CHANGED -> setOf("orderLineId", "carrierCode")
            WarehouseAllocationEventType.PICKER_CAPACITY_CHANGED,
            WarehouseAllocationEventType.WAREHOUSE_INCIDENT -> setOf("warehouseId")
        }
        require(request.target.keys.all { it in targetKeys })
        val payloadKeys = when (type) {
            WarehouseAllocationEventType.INVENTORY_ADJUSTED -> setOf("onHandQuantity")
            WarehouseAllocationEventType.RESERVATION_REJECTED -> setOf("reasonCode")
            WarehouseAllocationEventType.ORDER_CANCELLED -> setOf("lineRevision")
            WarehouseAllocationEventType.CARRIER_CUTOFF_CHANGED -> setOf("cutoffAt")
            WarehouseAllocationEventType.PICKER_CAPACITY_CHANGED -> setOf("capacity", "effectiveAt")
            WarehouseAllocationEventType.WAREHOUSE_INCIDENT -> setOf("incidentCode", "active")
        }
        require(request.payload.keys.all { it in payloadKeys })
        require(request.payload.keys.containsAll(payloadKeys))
        val target = EventTarget(
            warehouseId = request.target["warehouseId"]?.let(::WarehouseId),
            sku = request.target["sku"]?.let(::Sku),
            reservationId = request.target["reservationId"]?.let(::ReservationId),
            orderLineId = request.target["orderLineId"]?.let(::OrderLineId),
            carrierCode = request.target["carrierCode"],
        )
        val payload = when (type) {
            WarehouseAllocationEventType.INVENTORY_ADJUSTED -> WarehouseAllocationEventPayload.InventoryAdjusted((request.payload["onHandQuantity"] as Number).toInt())
            WarehouseAllocationEventType.RESERVATION_REJECTED -> WarehouseAllocationEventPayload.ReservationRejected(io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReservationRejectReason.valueOf(request.payload["reasonCode"].toString()))
            WarehouseAllocationEventType.ORDER_CANCELLED -> WarehouseAllocationEventPayload.OrderCancelled((request.payload["lineRevision"] as Number).toLong())
            WarehouseAllocationEventType.CARRIER_CUTOFF_CHANGED -> WarehouseAllocationEventPayload.CarrierCutoffChanged(Instant.parse(request.payload["cutoffAt"].toString()))
            WarehouseAllocationEventType.PICKER_CAPACITY_CHANGED -> WarehouseAllocationEventPayload.PickerCapacityChanged((request.payload["capacity"] as Number).toInt(), Instant.parse(request.payload["effectiveAt"].toString()))
            WarehouseAllocationEventType.WAREHOUSE_INCIDENT -> WarehouseAllocationEventPayload.WarehouseIncident(request.payload["incidentCode"]?.toString() ?: "WAREHOUSE_INCIDENT", request.payload["active"] as Boolean)
        }
        WarehouseAllocationEvent(EventId(request.eventId), EventKey(request.eventKey), type, target, request.sourceEventRevision, payload)
    } catch (error: Exception) {
        throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "event request is invalid", cause = error)
    }
}
