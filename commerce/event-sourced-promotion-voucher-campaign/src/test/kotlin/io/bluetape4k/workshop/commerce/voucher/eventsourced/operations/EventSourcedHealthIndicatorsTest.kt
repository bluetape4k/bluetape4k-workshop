package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status

internal class EventSourcedHealthIndicatorsTest {

    @Test
    fun `startup authority failure and shutdown alone refuse readiness`() {
        val state = EventSourcedOperationalState()
        val readiness = EventSourcedReadinessHealthIndicator(state)

        readiness.health().status shouldBeEqualTo Status.OUT_OF_SERVICE
        state.markReady()
        readiness.health().status shouldBeEqualTo Status.UP
        state.markAuthorityFailure()
        readiness.health().status shouldBeEqualTo Status.DOWN
        state.beginShutdown()
        readiness.health().status shouldBeEqualTo Status.OUT_OF_SERVICE
    }

    @Test
    fun `aggregate projection and every rebuild state remain separately diagnosable without dropping readiness`() {
        val state = EventSourcedOperationalState()
        val readiness = EventSourcedReadinessHealthIndicator(state)
        val aggregate = EventSourcedAggregateHealthIndicator(state)
        val projection = EventSourcedProjectionHealthIndicator(state)
        state.markReady()

        state.degradeAggregate()
        aggregate.health().status shouldBeEqualTo EVENT_SOURCED_DEGRADED_STATUS
        readiness.health().status shouldBeEqualTo Status.UP
        state.projectionHealth(degraded = true, rebuildState = ProjectionGenerationState.FAILED)
        projection.health().status shouldBeEqualTo EVENT_SOURCED_DEGRADED_STATUS
        readiness.health().status shouldBeEqualTo Status.UP

        ProjectionGenerationState.entries.forEach { rebuildState ->
            state.rebuildState(rebuildState)
            projection.health().details["rebuildState"] shouldBeEqualTo rebuildState.name
            readiness.health().status shouldBeEqualTo Status.UP
        }
    }
}
