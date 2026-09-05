package io.bluetape4k.workshop.graph.abuser.model

import java.io.Serializable

/**
 * PageRank 의심 점수와 그 점수를 계산한 실행 경로를 한 호출 단위로 묶은 결과입니다.
 *
 * 호출자는 별도의 공유 상태를 조회하지 않고도 [execution]이 [scores]에 정확히
 * 대응함을 보장받습니다.
 */
data class SuspiciousUserRanking(
    /** 점수 내림차순으로 정렬된 의심 사용자 점수입니다. */
    val scores: List<SuspiciousUserScore>,
    /** 이 점수를 계산할 때 선택한 알고리즘 실행 경로입니다. */
    val execution: AbuserAlgorithmExecution,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
