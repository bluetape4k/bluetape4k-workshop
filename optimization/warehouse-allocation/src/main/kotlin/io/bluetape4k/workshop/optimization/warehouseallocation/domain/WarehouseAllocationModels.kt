package io.bluetape4k.workshop.optimization.warehouseallocation.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant

internal data class Warehouse(
    val warehouseId: WarehouseId,
    val name: String,
    val timezone: String = "UTC",
    val capabilities: Set<WarehouseCapability> = emptySet(),
    val pickerCapacity: Int,
    val revision: Long = 0,
    val incident: Boolean = false,
) {
    init {
        require(pickerCapacity in 0..WarehouseAllocationLimits.MAX_QUANTITY)
        require(revision >= 0)
    }
}

internal data class SkuStockSnapshot(
    val warehouseId: WarehouseId,
    val sku: Sku,
    val onHandQuantity: Int,
    val reservedQuantity: Int = 0,
    val stockRevision: Long = 0,
    val sourceEventRevision: Long = 0,
    val handlingCapabilities: Set<WarehouseCapability> = emptySet(),
    val updatedAt: Instant = Instant.EPOCH,
) {
    init {
        require(onHandQuantity in 0..WarehouseAllocationLimits.MAX_QUANTITY)
        require(reservedQuantity in 0..onHandQuantity)
        require(stockRevision >= 0 && sourceEventRevision >= 0)
    }
    @get:JsonIgnore
    val availableQuantity: Int get() = onHandQuantity - reservedQuantity
}

internal data class OrderLine(
    val orderLineId: OrderLineId,
    val sku: Sku,
    val requestedQuantity: Int,
    val shippingRule: ShippingRule = ShippingRule.STANDARD,
    val carrierCutoff: Instant? = null,
    val status: OrderLineStatus = OrderLineStatus.OPEN,
    val revision: Long = 0,
    val orderId: OrderId? = null,
) {
    init {
        require(requestedQuantity in 1..WarehouseAllocationLimits.MAX_QUANTITY)
        require(revision >= 0)
    }
}

internal data class Order(
    val orderId: OrderId,
    val lines: List<OrderLine>,
    val status: OrderStatus = OrderStatus.OPEN,
    val revision: Long = 0,
) {
    init { require(lines.isNotEmpty()); require(revision >= 0) }
}

internal data class PickWave(
    val waveId: WaveId,
    val warehouseId: WarehouseId,
    val cutoffAt: Instant,
    val maxLines: Int,
    val warehouseCapacityRevision: Long = 0,
    val allocationIds: List<OrderLineId> = emptyList(),
    val revision: Long = 0,
    val status: PickWaveStatus = PickWaveStatus.OPEN,
) {
    init { require(maxLines >= 0); require(warehouseCapacityRevision >= 0); require(revision >= 0) }
}

internal data class CommittedAllocationPin(
    val pinId: PinId,
    val orderLineId: OrderLineId,
    val warehouseId: WarehouseId,
    val quantity: Int,
    val pinRevision: Long = 0,
    val createdBy: String = "system",
    val status: PinStatus = PinStatus.ACTIVE,
) {
    init { require(quantity in 1..WarehouseAllocationLimits.MAX_QUANTITY); require(pinRevision >= 0) }
}

internal data class Allocation(
    val orderLineId: OrderLineId,
    val warehouseId: WarehouseId,
    val waveId: WaveId,
    val quantity: Int,
) {
    init { require(quantity in 1..WarehouseAllocationLimits.MAX_QUANTITY) }
}

internal data class ScoreSummary(val hard: Int, val medium: Int, val soft: Int) {
    fun bounded(): ScoreSummary = copy(hard = hard.coerceIn(-WarehouseAllocationLimits.MAX_OUTPUT, 0))
    fun wire(): String = "hard=$hard;medium=$medium;soft=$soft"
}

internal data class PlanProposal(
    val planId: PlanId,
    val datasetId: DatasetId,
    val datasetVersion: Long,
    val expectedOrderRevision: Long,
    val warehouseRevision: Long,
    val hardScore: Int,
    val mediumScore: Int,
    val softScore: Int,
    val allocations: List<Allocation>,
    val unassignedReasons: Map<OrderLineId, WarehouseAllocationReasonCode>,
    val splitShipmentReasons: Set<OrderLineId> = emptySet(),
    val manualPins: List<CommittedAllocationPin> = emptyList(),
    val planRevision: Long = 0,
    val parentPlanRevision: Long? = null,
    val requestGeneration: Long = 0,
    val fencingToken: Long = 0,
    val status: PlanStatus = PlanStatus.DRAFT,
    val digest: String = "",
) {
    init {
        require(datasetVersion >= 0 && expectedOrderRevision >= 0 && warehouseRevision >= 0)
        require(planRevision >= 0 && parentPlanRevision?.let { it >= 0 } != false)
        require(requestGeneration >= 0 && fencingToken >= 0)
        require(allocations.size <= WarehouseAllocationLimits.MAX_OUTPUT)
        require(manualPins.size <= WarehouseAllocationLimits.MAX_PINS)
    }
}

internal data class WarehouseAllocationPlannerInput(
    val datasetId: DatasetId,
    val datasetVersion: Long,
    val expectedOrderRevision: Long,
    val warehouses: List<Warehouse>,
    val stocks: List<SkuStockSnapshot>,
    val orders: List<Order>,
    val waves: List<PickWave>,
    val pins: List<CommittedAllocationPin> = emptyList(),
    val planId: PlanId = PlanId("plan-$datasetVersion"),
    val seed: Long = 0,
)

internal data class WarehouseAllocationPlannerOutput(
    val proposal: PlanProposal,
    val digest: String,
)
