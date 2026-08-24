package io.bluetape4k.workshop.optimization.lastmile.provider

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** raw provider body 대신 정규화된 callback 결과만 digest합니다. */
object RoutingCallbackCanonicalizer {
    fun canonicalize(callback: RoutingCallback): String = buildString {
        append(callback.provider)
        append('|').append(callback.eventId.value)
        append('|').append(callback.requestId)
        append('|').append(callback.providerRevision.value)
        append('|').append(callback.result.proposal.planId.value)
        append('|').append(callback.result.proposal.planRevision)
        append('|').append(callback.result.proposal.matrixRevision)
        append('|').append(callback.result.proposal.score.hardScore)
        append('|').append(callback.result.proposal.score.softScore)
        callback.result.proposal.routes
            .sortedBy { it.vehicleId.value }
            .forEach { route ->
                append("|vehicle=").append(route.vehicleId.value)
                route.stops.sortedBy { it.sequence }.forEach { stop ->
                    append("|stop=").append(stop.jobId.value)
                    append(':').append(stop.kind.name)
                    append(':').append(stop.coordinateId.value)
                    append(':').append(stop.sequence)
                    append(':').append(stop.eta)
                    append(':').append(stop.loadAfter)
                    append(':').append(stop.pinned)
                }
            }
        callback.result.proposal.unassigned
            .sortedBy { it.jobId.value }
            .forEach { item -> append("|unassigned=").append(item.jobId.value).append(':').append(item.reason.name) }
    }

    fun digest(callback: RoutingCallback): String = sha256(canonicalize(callback))

    fun matches(callback: RoutingCallback): Boolean =
        MessageDigest.isEqual(
            callback.payloadDigest.toByteArray(StandardCharsets.US_ASCII),
            digest(callback).toByteArray(StandardCharsets.US_ASCII),
        )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
