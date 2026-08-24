package io.bluetape4k.workshop.optimization.lastmile.provider

import io.bluetape4k.workshop.optimization.lastmile.domain.ProviderRevision
import java.util.concurrent.ConcurrentHashMap

/**
 * 네트워크 없이 고정 planner 결과를 반환하는 provider fixture입니다.
 * 입력 digest와 matrix revision이 같으면 같은 결과를 반환합니다.
 */
class DeterministicRoutingProvider(
    private val planner: io.bluetape4k.workshop.optimization.lastmile.planner.DeterministicLastMilePlanner =
        io.bluetape4k.workshop.optimization.lastmile.planner.DeterministicLastMilePlanner(),
) : RoutingProvider {
    private val results = ConcurrentHashMap<String, RoutingResult>()

    override fun submit(request: RoutingRequest): RoutingSubmission {
        val proposal = planner.plan(request.input)
        results.putIfAbsent(
            request.requestId,
            RoutingResult(
                provider = NAME,
                requestId = request.requestId,
                providerRevision = ProviderRevision(request.input.matrix.revision),
                proposal = proposal,
            ),
        )
        return RoutingSubmission(NAME, request.requestId, request.input.requestGeneration)
    }

    override fun poll(submission: RoutingSubmission): RoutingResult? = results[submission.requestId]

    override fun acceptCallback(callback: RoutingCallback): CallbackDecision =
        if (callback.provider == NAME && callback.result.provider == NAME) {
            CallbackDecision.ACCEPTED
        } else {
            CallbackDecision.DIGEST_CONFLICT
        }

    companion object {
        const val NAME = "deterministic"
    }
}
