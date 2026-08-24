package io.bluetape4k.workshop.optimization.lastmile.provider

import io.bluetape4k.workshop.optimization.lastmile.domain.EventId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanProposal
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlannerInput
import io.bluetape4k.workshop.optimization.lastmile.domain.ProviderRevision

data class RoutingRequest(
    val requestId: String,
    val input: LastMilePlannerInput,
) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9._:-]{1,96}"))) { "request id must be bounded" }
    }
}

data class RoutingSubmission(
    val provider: String,
    val requestId: String,
    val requestGeneration: Long,
)

data class RoutingResult(
    val provider: String,
    val requestId: String,
    val providerRevision: ProviderRevision,
    val proposal: LastMilePlanProposal,
)

data class RoutingCallback(
    val provider: String,
    val eventId: EventId,
    val requestId: String,
    val providerRevision: ProviderRevision,
    val payloadDigest: String,
    val result: RoutingResult,
) {
    init {
        require(payloadDigest.matches(Regex("[0-9a-f]{64}"))) { "callback digest must be SHA-256" }
        require(provider == result.provider) { "callback provider must match result provider" }
        require(requestId == result.requestId) { "callback request id must match result request id" }
        require(providerRevision == result.providerRevision) { "callback revision must match result revision" }
    }
}

enum class CallbackDecision {
    ACCEPTED,
    DUPLICATE,
    DIGEST_CONFLICT,
    STALE_PROVIDER_REVISION,
}

interface RoutingProvider {
    fun submit(request: RoutingRequest): RoutingSubmission

    fun poll(submission: RoutingSubmission): RoutingResult?

    /** provider가 정규화된 callback envelope을 수락할 수 있는지 판정합니다. */
    fun acceptCallback(callback: RoutingCallback): CallbackDecision = CallbackDecision.ACCEPTED
}
