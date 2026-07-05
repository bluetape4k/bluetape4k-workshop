package io.bluetape4k.workshop.graph.recommendation.model

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * A ranked product recommendation produced by the collaborative filtering algorithm.
 *
 * ## Behavior / Contract
 * - [score] equals the number of distinct co-buyers who purchased both the seed user's
 *   products and [product]. Higher is better.
 * - [sharedBuyers] contains the actual co-buyer vertices that drove the score.
 *   Its size equals [score].
 * - Results are sorted by [score] descending, then by `productId` ascending for
 *   deterministic tie-breaking.
 *
 * ## Usage
 * ```kotlin
 * val recs = service.recommendProducts(alice.id)
 * recs.forEach { rec ->
 *     println("${rec.product} — score=${rec.score}, buyers=${rec.sharedBuyers.size}")
 * }
 * ```
 */
data class ProductRecommendation(
    /** The recommended product vertex. */
    val product: GraphVertex,
    /** Count of distinct co-buyers who purchased both [product] and at least one of the seed user's products. */
    val score: Int,
    /** Distinct co-buyer vertices that drove the [score]. */
    val sharedBuyers: List<GraphVertex>,
) : Serializable {
    init {
        score.requirePositiveNumber("score")
        sharedBuyers.size.requireEquals(score, "sharedBuyers.size")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
