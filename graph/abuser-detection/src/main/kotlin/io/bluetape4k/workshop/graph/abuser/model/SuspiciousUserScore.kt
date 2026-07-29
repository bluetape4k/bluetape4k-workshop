package io.bluetape4k.workshop.graph.abuser.model

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * 단일 사용자 정점에 대한 PageRank 기반 의심 점수입니다.
 *
 * ## 동작 / 계약
 * - [rank]는 1부터 시작합니다. `1`은 가장 의심스러운 사용자, `2`는 두 번째입니다.
 * - [score]는 하위 그래프 백엔드의 원시 PageRank 값이며, 높을수록 더 의심스럽습니다.
 * - [io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService.rankSuspiciousUsers] 결과는
 *   점수 내림차순으로 미리 정렬되므로 목록은 가장 의심스러운 사용자부터 덜 의심스러운 사용자 순서입니다.
 *
 * ## 사용 예
 * ```kotlin
 * val top10 = service.rankSuspiciousUsers(limit = 10)
 * top10.forEach { score ->
 *     println("#${score.rank} userId=${score.user.id} score=${score.score}")
 * }
 * ```
 */
data class SuspiciousUserScore(
    /** 사용자 정점입니다. */
    val user: GraphVertex,
    /** 원시 PageRank 점수입니다(높을수록 더 의심스럽습니다). */
    val score: Double,
    /** 의심 순위에서 1부터 시작하는 순위 위치입니다. */
    val rank: Int,
) : Serializable {
    init {
        score.requirePositiveNumber("score")
        rank.requirePositiveNumber("rank")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
