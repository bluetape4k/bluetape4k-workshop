package io.bluetape4k.workshop.graph.social.model

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * FOAF (Friend-of-a-Friend) recommendation result.
 *
 * @param person the recommended Person vertex
 * @param mutualConnectionCount number of shared direct KNOWS connections with the seed person
 * @param mutualConnections the shared direct connection vertices
 *
 * ## Behavior / Contract
 * - Results are sorted by [mutualConnectionCount] descending, then by `personId` domain key ascending
 *   for deterministic cross-backend ordering when counts are tied.
 * - A depth-2 FOAF candidate always has [mutualConnectionCount] >= 1 by definition.
 *
 * ## Usage
 * ```kotlin
 * val recommendations = service.recommendConnections(aliceId)
 * recommendations.forEach { rec ->
 *     println("${rec.person.properties["name"]} — ${rec.mutualConnectionCount} mutual connections")
 * }
 * ```
 */
data class ConnectionRecommendation(
    val person: GraphVertex,
    val mutualConnectionCount: Int,
    val mutualConnections: List<GraphVertex>,
) : Serializable {
    init {
        mutualConnectionCount.requirePositiveNumber("mutualConnectionCount")
        mutualConnections.size.requireEquals(mutualConnectionCount, "mutualConnections.size")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
