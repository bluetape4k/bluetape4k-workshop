package io.bluetape4k.workshop.optimization.warehouseallocation.planner

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.DatasetId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Order
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLine
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OrderLineId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PickWave
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.ShippingRule
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Sku
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.SkuStockSnapshot
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.Warehouse
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationPlannerInput
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationReasonCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseCapability
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseId
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WaveId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class WarehouseAllocationPlannerTest {
    private val input = WarehouseAllocationPlannerInput(
        DatasetId("d"), 1, 0,
        warehouses = listOf(Warehouse(WarehouseId("w-1"), "one", capabilities = setOf(WarehouseCapability.COLD_CHAIN), pickerCapacity = 3)),
        stocks = listOf(SkuStockSnapshot(WarehouseId("w-1"), Sku("sku"), 10)),
        orders = listOf(Order(OrderId("o-1"), listOf(OrderLine(OrderLineId("line-1"), Sku("sku"), 2, ShippingRule.COLD_CHAIN)))),
        waves = listOf(PickWave(WaveId("wave-1"), WarehouseId("w-1"), Instant.parse("2026-01-01T00:00:00Z"), 10)),
    )

    @Test
    fun `same input has byte identical allocation and digest`() {
        val planner = WarehouseAllocationPlanner()
        val left = planner.plan(input)
        val right = planner.plan(input)
        assertEquals(left, right)
        assertEquals(listOf(2), left.proposal.allocations.map { it.quantity })
    }

    @Test
    fun `existing wave allocation consumes picker capacity before proposing new lines`() {
        val constrained = input.copy(
            orders = listOf(Order(OrderId("o-2"), listOf(OrderLine(OrderLineId("line-2"), Sku("sku"), 1, ShippingRule.COLD_CHAIN)))),
            waves = listOf(input.waves.single().copy(maxLines = 1, allocationIds = listOf(OrderLineId("line-1")))),
        )
        val proposal = WarehouseAllocationPlanner().plan(constrained).proposal
        assertEquals(emptyList(), proposal.allocations)
        assertEquals(WarehouseAllocationReasonCode.PICKER_CAPACITY, proposal.unassignedReasons[OrderLineId("line-2")])
    }
}
