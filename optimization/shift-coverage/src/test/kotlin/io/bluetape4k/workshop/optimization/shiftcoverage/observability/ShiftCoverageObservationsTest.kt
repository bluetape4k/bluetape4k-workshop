package io.bluetape4k.workshop.optimization.shiftcoverage.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Test

class ShiftCoverageObservationsTest {
    @Test
    fun `bounded result tag is recorded without caller identifiers`() {
        val registry = SimpleMeterRegistry()
        val observations = ShiftCoverageObservations(ObservationRegistry.create(), registry)

        observations.recordCallback("worker-a-secret-token")
        observations.recordCallback("duplicate")
        repeat(100) { index -> observations.recordCallback("caller-$index") }

        registry.get("workshop.shift_coverage.callback.results").tag("result", "duplicate").counter().count() shouldBeEqualTo 1.0
        registry.get("workshop.shift_coverage.callback.results").tag("result", "other").counter().count() shouldBeEqualTo 101.0
    }
}
