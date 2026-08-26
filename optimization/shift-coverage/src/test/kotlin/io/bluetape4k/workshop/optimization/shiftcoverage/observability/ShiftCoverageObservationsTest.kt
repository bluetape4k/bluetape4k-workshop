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
        registry.meters.count { it.id.name == ShiftCoverageObservations.CALLBACK_COUNTER } shouldBeEqualTo 2
        registry.meters.filter { it.id.name == ShiftCoverageObservations.CALLBACK_COUNTER }
            .mapNotNull { it.id.getTag("result") }
            .toSet() shouldBeEqualTo setOf("duplicate", "other")
    }

    @Test
    fun `long running unknown results keep every observation family bounded`() {
        val registry = SimpleMeterRegistry()
        val observations = ShiftCoverageObservations(ObservationRegistry.create(), registry)

        repeat(10_000) { index ->
            val value = "caller-$index-secret"
            observations.recordPlan(value)
            observations.recordReplan(value)
            observations.recordApproval(value)
            observations.recordSwap(value)
            observations.recordCallback(value)
            observations.recordOutbox(value)
        }

        registry.meters.filter { it.id.name.startsWith("workshop.shift_coverage.") }
            .size shouldBeEqualTo 6
        registry.meters.filter { it.id.name.startsWith("workshop.shift_coverage.") }
            .all { it.id.getTag("result") == "other" } shouldBeEqualTo true
    }
}
