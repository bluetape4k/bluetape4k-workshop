package io.bluetape4k.workshop.optimization.warehouseallocation.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class WarehouseAllocationModelsTest {
    @Test
    fun `ids reject blank and oversized values`() {
        assertFailsWith<IllegalArgumentException> { WarehouseId("") }
        assertFailsWith<IllegalArgumentException> { DatasetId("x".repeat(97)) }
        assertFailsWith<IllegalArgumentException> { Sku("sku\n") }
    }

    @Test
    fun `available stock is on hand minus reserved`() {
        SkuStockSnapshot(WarehouseId("w"), Sku("s"), 10, 3).availableQuantity shouldBeEqualTo 7
    }

    @Test
    fun `closed status enums have exact wire values`() {
        PlanStatus.entries.map { it.name } shouldBeEqualTo listOf("DRAFT", "STALE", "APPROVED", "REJECTED", "CANCELLED")
        ReservationState.entries.map { it.name } shouldBeEqualTo listOf("PENDING", "ACCEPTED", "REJECTED", "RELEASED", "CANCELLED")
    }
}
