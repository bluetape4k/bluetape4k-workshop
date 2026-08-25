package io.bluetape4k.workshop.optimization.warehouseallocation.fixture

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class WarehouseAllocationFixtureAbiTest {
    @Test
    fun `fixture reset ingest and snapshot are deterministic`() {
        val fixture = DefaultWarehouseAllocationFixturePort()
        val dataset = fixture.reset(42)
        fixture.reset(42) shouldBeEqualTo dataset
        fixture.ingest("{\"event\":1}").startsWith("accepted:").shouldBeTrue()
        fixture.ingest("{\"event\":1}").startsWith("duplicate:").shouldBeTrue()
        fixture.snapshot(dataset).contains("\"reservations\":[]").shouldBeTrue()
    }
}
