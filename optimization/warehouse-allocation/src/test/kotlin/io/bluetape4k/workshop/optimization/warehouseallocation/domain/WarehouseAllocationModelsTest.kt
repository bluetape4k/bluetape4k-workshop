package io.bluetape4k.workshop.optimization.warehouseallocation.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WarehouseAllocationModelsTest {
    @Test
    fun `ids reject blank and oversized values`() {
        assertFailsWith<IllegalArgumentException> { WarehouseId("") }
        assertFailsWith<IllegalArgumentException> { DatasetId("x".repeat(97)) }
        assertFailsWith<IllegalArgumentException> { Sku("sku\n") }
    }

    @Test
    fun `available stock is on hand minus reserved`() {
        assertEquals(7, SkuStockSnapshot(WarehouseId("w"), Sku("s"), 10, 3).availableQuantity)
    }

    @Test
    fun `closed status enums have exact wire values`() {
        assertEquals(listOf("DRAFT", "STALE", "APPROVED", "REJECTED", "CANCELLED"), PlanStatus.entries.map { it.name })
        assertEquals(listOf("PENDING", "ACCEPTED", "REJECTED", "RELEASED", "CANCELLED"), ReservationState.entries.map { it.name })
    }
}
