package io.bluetape4k.workshop.optimization.planning.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.optimization.planning.application.PlanningCallbackDecision
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlanningObservationsTest {

    private val meterRegistry = SimpleMeterRegistry()
    private val observations = PlanningObservations(ObservationRegistry.create(), meterRegistry)

    @Test
    fun `records bounded provider outbox and callback outcomes`() {
        observations.observeProviderSubmission(PlanningProvider.FAKE) { "accepted" } shouldBeEqualTo "accepted"
        observations.recordOutbox(PlanningOutboxStatus.COMPLETED)
        observations.recordCallback(PlanningCallbackDecision.ACCEPTED)

        counter(PlanningObservations.PROVIDER_SUBMIT_COUNTER, "provider", "fake", "result", "success") shouldBeEqualTo 1.0
        counter(PlanningObservations.OUTBOX_COUNTER, "result", "completed") shouldBeEqualTo 1.0
        counter(PlanningObservations.CALLBACK_COUNTER, "result", "accepted") shouldBeEqualTo 1.0
    }

    @Test
    fun `records provider failure and preserves the exception`() {
        assertThrows<IllegalStateException> {
            observations.observeProviderSubmission(PlanningProvider.CUSTOM_SOLVER) {
                error("provider unavailable")
            }
        }

        counter(
            PlanningObservations.PROVIDER_SUBMIT_COUNTER,
            "provider",
            "custom_solver",
            "result",
            "failure",
        ) shouldBeEqualTo 1.0
    }

    private fun counter(name: String, vararg tags: String): Double =
        requireNotNull(meterRegistry.find(name).tags(*tags).counter()).count()
}
