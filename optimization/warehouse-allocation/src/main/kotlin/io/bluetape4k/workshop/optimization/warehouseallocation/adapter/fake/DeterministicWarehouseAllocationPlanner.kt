package io.bluetape4k.workshop.optimization.warehouseallocation.adapter.fake

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationPlannerInput
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationPlannerOutput
import io.bluetape4k.workshop.optimization.warehouseallocation.planner.WarehouseAllocationPlanner

internal class DeterministicWarehouseAllocationPlanner(
    private val delegate: WarehouseAllocationPlanner = WarehouseAllocationPlanner(),
) {
    fun plan(input: WarehouseAllocationPlannerInput): WarehouseAllocationPlannerOutput = delegate.plan(input)
}
