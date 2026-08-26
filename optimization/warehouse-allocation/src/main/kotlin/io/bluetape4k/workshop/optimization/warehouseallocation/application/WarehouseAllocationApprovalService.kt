package io.bluetape4k.workshop.optimization.warehouseallocation.application

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PickWaveStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanStatus
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ReservationState
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationNextAction
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationLimits
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.isAvailableFor
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.supports
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationReservationRecord
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service

internal data class WarehouseAllocationApprovalResult(
    val planId: String,
    val status: PlanStatus,
    val reservationIds: List<String>,
    val requestId: String,
)

@Service
internal class WarehouseAllocationApprovalService(
    private val repository: WarehouseAllocationRepository,
) {
    fun approve(planId: String, expectedPlanRevision: Long, requestId: String): WarehouseAllocationApprovalResult = transaction {
        val plan = repository.findPlan(planId)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown plan")
        if (plan.status != PlanStatus.DRAFT) {
            return@transaction WarehouseAllocationApprovalResult(planId, plan.status, repository.reservationsForPlan(planId).map { it.reservationId }, requestId)
        }
        if (plan.planRevision != expectedPlanRevision) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "plan revision changed")
        }

        val allocationGroups = plan.allocations.groupBy { it.orderLineId }.toSortedMap(compareBy { it.value })
        val lines = allocationGroups.mapValues { (lineId, allocations) ->
            val line = repository.findOrderLine(lineId.value)
                ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown order line")
            if (line.status !in setOf(OrderLineStatus.OPEN, OrderLineStatus.PARTIALLY_ALLOCATED)) {
                throw conflict("order line is not approvable")
            }
            val orderId = line.orderId?.value ?: throw conflict("order line has no parent order")
            val order = repository.findOrder(orderId) ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown order")
            if (order.status !in setOf(io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderStatus.OPEN, io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderStatus.PARTIALLY_ALLOCATED) ||
                order.revision != plan.expectedOrderRevision
            ) throw conflict("order revision or status changed")

            val activePin = repository.activePin(lineId.value)
            val plannedPin = plan.manualPins.firstOrNull { it.orderLineId == lineId }
            if ((activePin == null) != (plannedPin == null)) throw conflict("allocation pin changed")
            if (activePin != null && plannedPin != null &&
                (plannedPin.pinRevision != activePin.pinRevision ||
                    plannedPin.warehouseId != activePin.warehouseId || plannedPin.quantity != activePin.quantity ||
                    plannedPin.status != activePin.status)
            ) throw conflict("allocation pin changed")

            validateAllocations(line, allocations, activePin, plan)
            line to order
        }

        val stockQuantities = allocationGroups.flatMap { (lineId, allocations) ->
            val line = lines.getValue(lineId).first
            allocations.map { allocation ->
                StockKey(allocation.warehouseId.value, line.sku.value) to allocation.quantity
            }
        }.groupingBy { it.first }.fold(0) { total, entry -> total + entry.second }
        stockQuantities.toSortedMap(compareBy({ it.warehouseId }, { it.sku })).forEach { (key, quantity) ->
            val stock = repository.findStock(key.warehouseId, key.sku)
                ?: throw conflict("stock not found")
            if (!repository.reserveStockCas(key.warehouseId, key.sku, quantity, stock.stockRevision)) {
                throw conflict("stock revision or quantity conflict")
            }
        }

        val allocationsByWave = plan.allocations.groupBy { it.waveId }.toSortedMap(compareBy { it.value })
        allocationsByWave.forEach { (waveId, allocations) ->
            val wave = repository.findWave(waveId.value) ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown pick wave")
            val allocationIds = (wave.allocationIds + allocations.map { it.orderLineId }).distinct().sortedBy { it.value }
            val nextWave = wave.copy(allocationIds = allocationIds, revision = wave.revision + 1)
            if (!repository.updateWaveIfRevision(wave.waveId.value, wave.revision, nextWave)) {
                throw conflict("pick wave changed during approval")
            }
        }

        val reservationIds = mutableListOf<String>()
        allocationGroups.toSortedMap(compareBy { it.value }).forEach { (lineId, allocations) ->
            val line = lines.getValue(lineId).first
            if (!repository.claimActivePlan(lineId.value, line.revision, planId)) {
                throw WarehouseAllocationException(WarehouseAllocationErrorCode.ACTIVE_PLAN_CONFLICT, "another plan is active")
            }
            val acceptedRevision = line.revision + 1
            allocations.sortedWith(compareBy({ it.warehouseId.value }, { it.waveId.value })).forEachIndexed { index, allocation ->
                val reservationId = "res-${planId.take(64)}-${lineId.value.take(64)}-$index"
                if (!repository.insertReservation(
                        WarehouseAllocationReservationRecord(
                            reservationId, planId, lineId.value, allocation.warehouseId.value, line.sku.value,
                            allocation.quantity, ReservationState.ACCEPTED, 0,
                        ),
                    ) && repository.reservations(lineId.value).none { it.reservationId == reservationId }
                ) throw conflict("reservation key already exists")
                reservationIds += reservationId
            }
            val allocated = allocations.sumOf { it.quantity }
            val nextStatus = if (allocated >= line.requestedQuantity) OrderLineStatus.ALLOCATED else OrderLineStatus.PARTIALLY_ALLOCATED
            if (!repository.updateOrderLine(lineId.value, acceptedRevision, line.copy(status = nextStatus, revision = acceptedRevision + 1))) {
                throw conflict("order line changed during approval")
            }
        }

        lines.values.map { it.second }.distinctBy { it.orderId }.sortedBy { it.orderId.value }.forEach { order ->
            val current = repository.findOrder(order.orderId.value) ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown order")
            val status = repository.projectOrderStatus(current.lines)
            if (!repository.updateOrderIfRevision(order.orderId.value, order.revision, current.copy(status = status, revision = order.revision + 1))) {
                throw conflict("order changed during approval")
            }
        }

        val next = plan.copy(status = PlanStatus.APPROVED, planRevision = plan.planRevision + 1)
        if (!repository.updatePlanStatus(planId, expectedPlanRevision, next)) {
            throw conflict("plan changed during approval")
        }
        val operationKey = "approve-${planId.take(120)}-${next.planRevision}"
        repository.appendAudit(requestId, "plan", planId, "APPROVED", "inventory reservation committed")
        repository.enqueueOutbox(operationKey, "plan-approved", "plan:$planId", next.planRevision, "{\"planId\":\"${planId.take(120)}\",\"status\":\"APPROVED\"}")
        WarehouseAllocationApprovalResult(planId, PlanStatus.APPROVED, reservationIds, requestId)
    }

    fun reject(planId: String, expectedPlanRevision: Long, reasonCode: String, requestId: String): WarehouseAllocationApprovalResult = transaction {
        val plan = repository.findPlan(planId)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown plan")
        if (plan.status != PlanStatus.DRAFT) {
            return@transaction WarehouseAllocationApprovalResult(planId, plan.status, repository.reservationsForPlan(planId).map { it.reservationId }, requestId)
        }
        if (plan.planRevision != expectedPlanRevision) throw conflict("plan revision changed")
        val next = plan.copy(status = PlanStatus.REJECTED, planRevision = expectedPlanRevision + 1)
        if (!repository.updatePlanStatus(planId, expectedPlanRevision, next)) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.RESERVATION_CONFLICT, "plan changed during rejection")
        }
        repository.appendAudit(requestId, "plan", planId, "REJECTED", "operator reason=${reasonCode.take(64)}")
        WarehouseAllocationApprovalResult(planId, PlanStatus.REJECTED, emptyList(), requestId)
    }

    private data class StockKey(val warehouseId: String, val sku: String)

    private fun validateAllocations(
        line: io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLine,
        allocations: List<io.bluetape4k.workshop.optimization.warehouseallocation.domain.Allocation>,
        pin: io.bluetape4k.workshop.optimization.warehouseallocation.domain.CommittedAllocationPin?,
        plan: io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanProposal,
    ) {
        require(allocations.isNotEmpty() && allocations.size <= WarehouseAllocationLimits.MAX_OUTPUT)
        val newLinesByWave = allocations.groupBy { it.waveId }.mapValues { (_, values) -> values.map { it.orderLineId }.toSet() }
        allocations.sortedWith(compareBy({ it.warehouseId.value }, { it.waveId.value })).forEach { allocation ->
            val warehouse = repository.findWarehouse(allocation.warehouseId.value) ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown warehouse")
            if (!warehouse.supports(line.shippingRule)) throw conflict("warehouse capability or incident changed")
            val wave = repository.findWave(allocation.waveId.value) ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown pick wave")
            if (wave.status != PickWaveStatus.OPEN || !wave.isAvailableFor(line, warehouse)) throw conflict("pick wave cutoff or capacity revision changed")
            val existing = repository.allocationLineCount(wave.waveId.value)
            val added = newLinesByWave.getValue(wave.waveId).size
            if (existing + added > wave.maxLines) throw conflict("picker capacity changed")
        }
        if (pin != null) {
            val pinnedQuantity = allocations.filter { it.warehouseId == pin.warehouseId }.sumOf { it.quantity }
            if (pinnedQuantity < pin.quantity || allocations.any { it.warehouseId != pin.warehouseId }) throw conflict("allocation pin constraint changed")
        }
        if (allocations.sumOf { it.quantity } > line.requestedQuantity) throw conflict("allocation exceeds requested quantity")
        if (allocations.any { it.quantity <= 0 }) throw conflict("allocation quantity is invalid")
    }

    private fun conflict(message: String): WarehouseAllocationException =
        WarehouseAllocationException(
            WarehouseAllocationErrorCode.RESERVATION_CONFLICT,
            message,
            nextAction = WarehouseAllocationNextAction.REPLAN,
        )
}
