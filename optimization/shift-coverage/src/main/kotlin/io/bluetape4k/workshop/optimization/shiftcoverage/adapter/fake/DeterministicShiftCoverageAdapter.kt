package io.bluetape4k.workshop.optimization.shiftcoverage.adapter.fake

import io.bluetape4k.codec.Base58
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageAcceptance
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageCallbackStatus
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoveragePlanningPort
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoveragePlanningRequest
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoveragePlanningCallback
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageProvider
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageSubmission
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.InvalidShiftCoverageInput
import io.bluetape4k.workshop.optimization.shiftcoverage.planner.DeterministicShiftCoveragePlanner

/** network/credential 없이 recorded fixture로만 동작하는 기본 provider adapter입니다. */
class DeterministicShiftCoverageAdapter(
    private val planner: DeterministicShiftCoveragePlanner = DeterministicShiftCoveragePlanner(),
) : ShiftCoveragePlanningPort {
    override fun submit(request: ShiftCoveragePlanningRequest): ShiftCoverageSubmission {
        if (request.provider != ShiftCoverageProvider.FAKE) {
            throw InvalidShiftCoverageInput("only FAKE provider is enabled in the demo profile")
        }
        val proposal = planner.plan(request.snapshot)
        return ShiftCoverageSubmission(
            providerRequestId = "fake-${Base58.randomString(12)}",
            proposal = proposal,
        )
    }

    override fun accept(callback: ShiftCoveragePlanningCallback): ShiftCoverageAcceptance {
        if (callback.provider != ShiftCoverageProvider.FAKE) return ShiftCoverageAcceptance(false, "provider is not enabled")
        return when (callback.status) {
            ShiftCoverageCallbackStatus.SUCCEEDED -> ShiftCoverageAcceptance(true)
            ShiftCoverageCallbackStatus.FAILED, ShiftCoverageCallbackStatus.REJECTED ->
                ShiftCoverageAcceptance(false, callback.reason ?: "provider rejected planning")
        }
    }
}
