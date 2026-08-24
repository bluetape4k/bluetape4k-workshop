package io.bluetape4k.workshop.optimization.lastmile.domain

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test

class LastMileIdsTest {

    @Test
    fun `identifiers reject unbounded and unsafe values`() {
        assertFailsWith<IllegalArgumentException> { JobId("") }
        assertFailsWith<IllegalArgumentException> { VehicleId("vehicle/1") }
        assertFailsWith<IllegalArgumentException> { DriverId("d".repeat(65)) }
    }

    @Test
    fun `versions reject negative values`() {
        assertFailsWith<IllegalArgumentException> { CarrierVersion(-1) }
        assertFailsWith<IllegalArgumentException> { ProviderRevision(-1) }
    }
}
