package io.bluetape4k.workshop.graph.abuser.model

import io.bluetape4k.graph.model.GraphVertex
import java.io.Serializable

/**
 * PageRank-based suspicion score for a single user vertex.
 *
 * ## Behavior / Contract
 * - [rank] is 1-based: `1` is the most suspicious user, `2` is second, and so on.
 * - [score] is the raw PageRank value from the underlying graph backend; higher is more suspicious.
 * - Results from [io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService.rankSuspiciousUsers]
 *   are pre-sorted descending by score, so the list is already ordered from most to least suspicious.
 *
 * ## Usage
 * ```kotlin
 * val top10 = service.rankSuspiciousUsers(limit = 10)
 * top10.forEach { score ->
 *     println("#${score.rank} userId=${score.user.id} score=${score.score}")
 * }
 * ```
 */
data class SuspiciousUserScore(
    /** The user vertex. */
    val user: GraphVertex,
    /** Raw PageRank score (higher = more suspicious). */
    val score: Double,
    /** 1-based rank position in the suspicion ranking. */
    val rank: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
