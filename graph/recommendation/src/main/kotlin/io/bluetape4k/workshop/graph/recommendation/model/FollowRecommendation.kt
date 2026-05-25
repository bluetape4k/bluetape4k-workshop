package io.bluetape4k.workshop.graph.recommendation.model

import io.bluetape4k.graph.model.GraphVertex
import java.io.Serializable

/**
 * A ranked follow recommendation produced by the FOAF (Friend-of-a-Friend) algorithm
 * on the FOLLOWS graph.
 *
 * ## Behavior / Contract
 * - [mutualFollowCount] is the number of people the seed user already follows
 *   who also follow [person]. Higher is better.
 * - [mutualFollows] contains the intermediary vertices that drove the count.
 *   Its size equals [mutualFollowCount].
 * - Results are sorted by [mutualFollowCount] descending, then by `userId` ascending
 *   for deterministic tie-breaking.
 *
 * ## Usage
 * ```kotlin
 * val recs = service.recommendFollows(alice.id)
 * recs.forEach { rec ->
 *     println("${rec.person} — mutualFollowCount=${rec.mutualFollowCount}")
 * }
 * ```
 */
data class FollowRecommendation(
    /** The recommended user vertex to follow. */
    val person: GraphVertex,
    /**
     * Count of people the seed user follows who also follow [person].
     * These are FOAF intermediaries, not symmetric mutual follows.
     */
    val mutualFollowCount: Int,
    /** The intermediary vertices (seed's follows who also follow [person]). */
    val mutualFollows: List<GraphVertex>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
