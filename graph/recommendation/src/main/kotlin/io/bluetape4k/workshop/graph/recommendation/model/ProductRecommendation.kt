package io.bluetape4k.workshop.graph.recommendation.model

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * collaborative filtering 알고리즘이 만든 순위화된 상품 추천입니다.
 *
 * ## 동작 / 계약
 * - [score]는 seed 사용자의 상품과 [product]를 모두 구매한 서로 다른 co-buyer 수입니다.
 *   값이 클수록 더 좋은 추천입니다.
 * - [sharedBuyers]에는 score를 만든 실제 co-buyer 정점이 들어갑니다. 크기는 [score]와 같습니다.
 * - 결과는 [score] 내림차순, `productId` 오름차순으로 정렬해 tie-breaking을 결정적으로 만듭니다.
 *
 * ## 사용 예
 * ```kotlin
 * val recs = service.recommendProducts(alice.id)
 * recs.forEach { rec ->
 *     println("${rec.product} — score=${rec.score}, buyers=${rec.sharedBuyers.size}")
 * }
 * ```
 */
data class ProductRecommendation(
    /** 추천된 Product 정점입니다. */
    val product: GraphVertex,
    /** [product]와 seed 사용자의 상품 중 하나 이상을 모두 구매한 서로 다른 co-buyer 수입니다. */
    val score: Int,
    /** [score]를 만든 서로 다른 co-buyer 정점입니다. */
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
