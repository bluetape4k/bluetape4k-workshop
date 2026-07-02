package io.bluetape4k.workshop.graph.recommendation.model

import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphVertex
import java.io.Serializable

/**
 * Reason why a candidate was excluded from the final recommendation list.
 */
enum class RecommendationExclusionReason {
    /** Product candidate was already purchased by the seed user. */
    ALREADY_PURCHASED,

    /** Follow candidate is already followed by the seed user. */
    ALREADY_FOLLOWED,

    /** Follow candidate is the seed user. */
    SELF,
}

/**
 * A candidate exclusion emitted with recommendation explanations.
 *
 * ## Behavior / Contract
 * - [candidateId] identifies the excluded product or user vertex.
 * - [reason] explains which rule removed the candidate from the final ranking.
 * - [via] identifies the intermediary vertex when the exclusion was discovered through
 *   a path, for example `alice -> bob -> alice` for [RecommendationExclusionReason.SELF].
 */
data class CandidateExclusion(
    val candidateId: GraphElementId,
    val reason: RecommendationExclusionReason,
    val via: GraphVertex? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Evidence path for a product recommendation.
 *
 * The path reads as: seed user bought [sharedProduct], [coBuyer] also bought that product,
 * and [coBuyer] bought [candidateProduct], making [candidateProduct] a recommendation candidate.
 */
data class ProductEvidencePath(
    val sharedProduct: GraphVertex,
    val coBuyer: GraphVertex,
    val candidateProduct: GraphVertex,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Product recommendation plus the evidence and exclusion rules used to produce it.
 *
 * ## Usage
 * ```kotlin
 * val explanations = service.explainProductRecommendations(alice.id)
 * explanations.first().evidencePaths.forEach { path ->
 *     println("${path.coBuyer} connects ${path.sharedProduct} to ${path.candidateProduct}")
 * }
 * ```
 */
data class ExplainedProductRecommendation(
    val recommendation: ProductRecommendation,
    val evidencePaths: List<ProductEvidencePath>,
    val excludedCandidates: List<CandidateExclusion>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Evidence path for a follow recommendation.
 *
 * The path reads as: seed user follows [intermediary], and [intermediary] follows [candidate].
 */
data class FollowEvidencePath(
    val intermediary: GraphVertex,
    val candidate: GraphVertex,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Follow recommendation plus the FOAF paths and exclusion rules used to produce it.
 */
data class ExplainedFollowRecommendation(
    val recommendation: FollowRecommendation,
    val evidencePaths: List<FollowEvidencePath>,
    val excludedCandidates: List<CandidateExclusion>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
