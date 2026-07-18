package io.bluetape4k.workshop.optimization.planning.adapter.fake

import io.bluetape4k.workshop.optimization.planning.domain.PlanningEngine
import io.bluetape4k.workshop.optimization.planning.domain.PlanningResult
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.domain.PlanningRevision
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import io.bluetape4k.workshop.optimization.planning.domain.PlanningSubmission
import io.bluetape4k.workshop.optimization.planning.domain.PlanningSubmissionResult
import io.bluetape4k.workshop.optimization.planning.domain.ProviderRequestId
import java.util.concurrent.ConcurrentHashMap

/**
 * Network-free planning engine that produces stable results from request ids.
 */
internal class DeterministicPlanningEngine: PlanningEngine {

    override val provider: PlanningProvider = PlanningProvider.FAKE

    private val results = ConcurrentHashMap<ProviderRequestId, PlanningResult>()

    override fun submit(request: PlanningSubmission): PlanningSubmissionResult {
        val providerRequestId = ProviderRequestId("fake-${request.requestId}")
        results.computeIfAbsent(providerRequestId) {
            PlanningResult(
                requestId = request.requestId,
                providerRequestId = providerRequestId,
                revision = PlanningRevision((request.parentRevision?.value ?: 0) + 1),
                status = PlanningStatus.SUCCEEDED,
                scoreSummary = "0hard/0medium/-1soft",
                constraintExplanations = listOf("workload balanced within configured limits"),
            )
        }
        return PlanningSubmissionResult(providerRequestId, PlanningStatus.SUBMITTED)
    }

    override fun status(providerRequestId: ProviderRequestId): PlanningResult? = results[providerRequestId]
}
