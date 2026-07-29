package io.bluetape4k.workshop.graph.recommendation.model

import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphVertex
import java.io.Serializable

/**
 * 후보가 최종 추천 목록에서 제외된 이유입니다.
 */
enum class RecommendationExclusionReason {
    /** Product 후보를 seed 사용자가 이미 구매했습니다. */
    ALREADY_PURCHASED,

    /** follow 후보를 seed 사용자가 이미 follow하고 있습니다. */
    ALREADY_FOLLOWED,

    /** follow 후보가 seed 사용자 자신입니다. */
    SELF,
}

/**
 * 추천 설명과 함께 내보내는 후보 제외 항목입니다.
 *
 * ## 동작 / 계약
 * - [candidateId]는 제외된 Product 또는 User 정점을 식별합니다.
 * - [reason]은 어떤 규칙이 후보를 최종 순위에서 제거했는지 설명합니다.
 * - [via]는 path를 통해 제외를 발견했을 때의 중간 정점을 식별합니다.
 *   예를 들어 [RecommendationExclusionReason.SELF]에서는 `alice -> bob -> alice`의 `bob`입니다.
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
 * 상품 추천의 증거 path입니다.
 *
 * path의 의미는 다음과 같습니다. seed 사용자가 [sharedProduct]를 구매했고, [coBuyer]도
 * 그 상품을 구매했으며, [coBuyer]가 [candidateProduct]도 구매했으므로
 * [candidateProduct]가 추천 후보가 됩니다.
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
 * 상품 추천과, 그 추천을 만들 때 사용한 증거 및 제외 규칙입니다.
 *
 * ## 사용 예
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
 * follow 추천의 증거 path입니다.
 *
 * path의 의미는 다음과 같습니다. seed 사용자가 [intermediary]를 follow하고,
 * [intermediary]가 [candidate]를 follow합니다.
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
 * follow 추천과, 그 추천을 만들 때 사용한 FOAF path 및 제외 규칙입니다.
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
