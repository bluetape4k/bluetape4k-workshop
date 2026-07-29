package io.bluetape4k.workshop.graph.social.model

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * FOAF(Friend-of-a-Friend) 추천 결과입니다.
 *
 * @param person 추천된 Person 정점입니다.
 * @param mutualConnectionCount seed Person과 공유하는 direct `KNOWS` 연결 수입니다.
 * @param mutualConnections 공유된 direct connection 정점입니다.
 *
 * ## 동작 / 계약
 * - 결과는 [mutualConnectionCount] 내림차순, `personId` 도메인 키 오름차순으로 정렬해
 *   count가 같을 때 backend를 가로질러 결정적인 순서를 유지합니다.
 * - depth-2 FOAF 후보는 정의상 항상 [mutualConnectionCount] >= 1입니다.
 *
 * ## 사용 예
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
