package io.bluetape4k.workshop.optimization.warehouseallocation.fixture

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldStartWith
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class WarehouseAllocationFixtureAbiTest {

    companion object: KLogging()

    @Test
    fun `fixture reset ingest and snapshot are deterministic`() {
        val fixture = DefaultWarehouseAllocationFixturePort()
        val dataset = fixture.reset(42)
        log.debug { "dataset=$dataset" }

        fixture.reset(42) shouldBeEqualTo dataset
        fixture.ingest("{\"event\":1}") shouldStartWith "accepted:"
        fixture.ingest("{\"event\":1}") shouldStartWith "duplicate:"
        fixture.snapshot(dataset) shouldContain "\"reservations\":[]"
    }
}
