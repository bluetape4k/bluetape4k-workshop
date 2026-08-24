package io.bluetape4k.workshop.optimization.warehouseallocation.fixture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarehouseAllocationFixtureAbiTest {
    @Test
    fun `fixture reset ingest and snapshot are deterministic`() {
        val fixture = DefaultWarehouseAllocationFixturePort()
        val dataset = fixture.reset(42)
        assertEquals(dataset, fixture.reset(42))
        assertTrue(fixture.ingest("{\"event\":1}").startsWith("accepted:"))
        assertTrue(fixture.ingest("{\"event\":1}").startsWith("duplicate:"))
        assertTrue(fixture.snapshot(dataset).contains("\"reservations\":[]"))
    }
}
