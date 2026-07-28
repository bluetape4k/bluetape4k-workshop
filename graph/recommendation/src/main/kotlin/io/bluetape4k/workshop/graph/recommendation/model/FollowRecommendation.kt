package io.bluetape4k.workshop.graph.recommendation.model

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * `FOLLOWS` 그래프의 FOAF(Friend-of-a-Friend) 알고리즘이 만든 순위화된 follow 추천입니다.
 *
 * ## 동작 / 계약
 * - [mutualFollowCount]는 seed 사용자가 이미 follow하고, 동시에 [person]도 follow하는 사람 수입니다.
 *   값이 클수록 더 좋은 추천입니다.
 * - [mutualFollows]에는 이 수치를 만든 중간 정점이 들어갑니다. 크기는 [mutualFollowCount]와 같습니다.
 * - 결과는 [mutualFollowCount] 내림차순, `userId` 오름차순으로 정렬해 tie-breaking을 결정적으로 만듭니다.
 *
 * ## 사용 예
 * ```kotlin
 * val recs = service.recommendFollows(alice.id)
 * recs.forEach { rec ->
 *     println("${rec.person} — mutualFollowCount=${rec.mutualFollowCount}")
 * }
 * ```
 */
data class FollowRecommendation(
    /** follow 대상으로 추천된 User 정점입니다. */
    val person: GraphVertex,
    /**
     * seed 사용자가 follow하고, 동시에 [person]도 follow하는 사람 수입니다.
     * 이는 대칭적인 mutual follow가 아니라 FOAF 중간 정점입니다.
     */
    val mutualFollowCount: Int,
    /** 중간 정점입니다. seed 사용자의 follow 대상 중 [person]도 follow하는 User입니다. */
    val mutualFollows: List<GraphVertex>,
) : Serializable {
    init {
        mutualFollowCount.requirePositiveNumber("mutualFollowCount")
        mutualFollows.size.requireEquals(mutualFollowCount, "mutualFollows.size")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
