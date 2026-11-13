package io.bluetape4k.workshop.optimization.warehouseallocation.planner

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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

class WarehouseAllocationPlannerTest {

    companion object: KLogging()

    private val input = WarehouseAllocationPlannerInput(
        datasetId = DatasetId("d"),
        datasetVersion = 1,
        expectedOrderRevision = 0,
        warehouses = listOf(
            Warehouse(
                warehouseId = WarehouseId("w-1"),
                name = "one",
                capabilities = setOf(WarehouseCapability.COLD_CHAIN),
                pickerCapacity = 3
            )
        ),
        stocks = listOf(SkuStockSnapshot(WarehouseId("w-1"), Sku("sku"), 10)),
        orders = listOf(
            Order(
                orderId = OrderId("o-1"),
                lines = listOf(
                    OrderLine(
                        orderLineId = OrderLineId("line-1"),
                        sku = Sku("sku"),
                        requestedQuantity = 2,
                        shippingRule = ShippingRule.COLD_CHAIN
                    )
                )
            )
        ),
        waves = listOf(
            PickWave(
                waveId = WaveId("wave-1"),
                warehouseId = WarehouseId("w-1"),
                cutoffAt = Instant.parse("2026-01-01T00:00:00Z"),
                maxLines = 10
            )
        ),
    )

    @Test
    fun `same input has byte identical allocation and digest`() {
        val planner = WarehouseAllocationPlanner()
        val left = planner.plan(input)
        val right = planner.plan(input)

        log.debug { "left=$left" }

        left shouldBeEqualTo right
        left.proposal.allocations.map { it.quantity } shouldBeEqualTo listOf(2)
    }

    @Test
    fun `existing wave allocation consumes picker capacity before proposing new lines`() {
        val constrained = input.copy(
            orders = listOf(
                Order(
                    orderId = OrderId("o-2"),
                    lines = listOf(
                        OrderLine(
                            orderLineId = OrderLineId("line-2"),
                            sku = Sku("sku"),
                            requestedQuantity = 1,
                            shippingRule = ShippingRule.COLD_CHAIN
                        )
                    )
                )
            ),
            waves = listOf(input.waves.single().copy(maxLines = 1, allocationIds = listOf(OrderLineId("line-1")))),
        )

        val proposal = WarehouseAllocationPlanner().plan(constrained).proposal

        log.debug { "proposal=$proposal" }
        proposal.allocations.shouldBeEmpty()
        proposal.unassignedReasons[OrderLineId("line-2")] shouldBeEqualTo WarehouseAllocationReasonCode.PICKER_CAPACITY
    }
}
