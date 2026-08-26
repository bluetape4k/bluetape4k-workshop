package io.bluetape4k.workshop.optimization.shiftcoverage.observability

import io.bluetape4k.micrometer.observation.withObservation
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.springframework.stereotype.Component

/** provider/tenant/worker 값을 tag로 남기지 않는 bounded observation facade입니다. */
@Component
class ShiftCoverageObservations(
    private val observationRegistry: ObservationRegistry,
    private val meterRegistry: MeterRegistry,
) {
    fun <T> observePlan(block: () -> T): T = withObservation(PLAN_OBSERVATION, observationRegistry) {
        try {
            block().also { recordPlan("success") }
        } catch (failure: Exception) {
            recordPlan("failure")
            throw failure
        }
    }

    fun recordPlan(result: String) = counter(PLAN_COUNTER, result)

    fun recordReplan(result: String) = counter(REPLAN_COUNTER, result)

    fun recordApproval(result: String) = counter(APPROVAL_COUNTER, result)

    fun recordSwap(result: String) = counter(SWAP_COUNTER, result)

    fun recordCallback(result: String) = counter(CALLBACK_COUNTER, result)

    fun recordOutbox(result: String) = counter(OUTBOX_COUNTER, result)

    private fun counter(name: String, result: String) {
        val bounded = result.lowercase().takeIf { it in ALLOWED_RESULTS } ?: "other"
        meterRegistry.counter(name, RESULT_TAG, bounded).increment()
    }

    companion object {
        const val PLAN_OBSERVATION = "workshop.shift_coverage.plan"
        const val PLAN_COUNTER = "workshop.shift_coverage.plan.attempts"
        const val REPLAN_COUNTER = "workshop.shift_coverage.replan.results"
        const val APPROVAL_COUNTER = "workshop.shift_coverage.approval.results"
        const val SWAP_COUNTER = "workshop.shift_coverage.swap.results"
        const val CALLBACK_COUNTER = "workshop.shift_coverage.callback.results"
        const val OUTBOX_COUNTER = "workshop.shift_coverage.outbox.results"
        private const val RESULT_TAG = "result"
        private val ALLOWED_RESULTS = setOf(
            "success", "failure", "accepted", "rejected", "conflict", "duplicate", "stale", "retry", "dead_letter", "other",
        )
    }
}
