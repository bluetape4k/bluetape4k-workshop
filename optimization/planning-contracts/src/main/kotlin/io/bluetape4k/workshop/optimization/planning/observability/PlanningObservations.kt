package io.bluetape4k.workshop.optimization.planning.observability

import io.bluetape4k.micrometer.observation.withObservation
import io.bluetape4k.workshop.optimization.planning.application.PlanningCallbackDecision
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.persistence.PlanningOutboxStatus
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.springframework.stereotype.Component

@Component
internal class PlanningObservations(
    private val observationRegistry: ObservationRegistry,
    private val meterRegistry: MeterRegistry,
) {

    fun <T> observeProviderSubmission(
        provider: PlanningProvider,
        block: () -> T,
    ): T = withObservation(PROVIDER_SUBMIT_OBSERVATION, observationRegistry) {
        try {
            block().also { recordProviderSubmission(provider, RESULT_SUCCESS) }
        } catch (failure: Exception) {
            recordProviderSubmission(provider, RESULT_FAILURE)
            throw failure
        }
    }

    fun recordOutbox(status: PlanningOutboxStatus) {
        meterRegistry.counter(OUTBOX_COUNTER, TAG_RESULT, status.name.lowercase()).increment()
    }

    fun recordCallback(decision: PlanningCallbackDecision) {
        meterRegistry.counter(CALLBACK_COUNTER, TAG_RESULT, decision.name.lowercase()).increment()
    }

    private fun recordProviderSubmission(provider: PlanningProvider, result: String) {
        meterRegistry.counter(
            PROVIDER_SUBMIT_COUNTER,
            TAG_PROVIDER,
            provider.name.lowercase(),
            TAG_RESULT,
            result,
        ).increment()
    }

    companion object {
        internal const val PROVIDER_SUBMIT_OBSERVATION = "workshop.planning.provider.submit"
        internal const val PROVIDER_SUBMIT_COUNTER = "workshop.planning.provider.submit.attempts"
        internal const val OUTBOX_COUNTER = "workshop.planning.outbox.results"
        internal const val CALLBACK_COUNTER = "workshop.planning.callback.results"
        private const val TAG_PROVIDER = "provider"
        private const val TAG_RESULT = "result"
        private const val RESULT_SUCCESS = "success"
        private const val RESULT_FAILURE = "failure"
    }
}
