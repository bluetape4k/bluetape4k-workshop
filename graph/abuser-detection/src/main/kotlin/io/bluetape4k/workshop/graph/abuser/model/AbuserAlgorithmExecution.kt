package io.bluetape4k.workshop.graph.abuser.model

import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecution
import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecutionPath
import io.bluetape4k.graph.algo.provider.GraphAlgorithmFallbackReason
import io.bluetape4k.graph.algo.provider.GraphAlgorithmId
import java.io.Serializable

/**
 * 어뷰저 점수 계산 한 건에 귀속된 그래프 알고리즘 실행 경로입니다.
 *
 * upstream 실행 모델에서 로그 주입에 악용될 수 있는 provider ID를 제한해
 * API 응답과 관찰 지점에 안전하게 전달합니다.
 */
data class AbuserAlgorithmExecution(
    /** 실행한 알고리즘입니다. */
    val algorithm: GraphAlgorithmId,
    /** 실행 provider 식별자입니다. 영문 소문자와 숫자로 시작하는 최대 64자의 안전 문자열입니다. */
    val providerId: String,
    /** native provider 또는 JVM fallback 중 실제로 선택한 경로입니다. */
    val path: GraphAlgorithmExecutionPath,
    /** JVM fallback을 선택한 이유이며 native 실행에는 존재하지 않습니다. */
    val fallbackReason: GraphAlgorithmFallbackReason?,
) : Serializable {
    init {
        require(PROVIDER_ID.matches(providerId)) {
            "providerId must match ${PROVIDER_ID.pattern}"
        }
        when (path) {
            GraphAlgorithmExecutionPath.NATIVE ->
                require(fallbackReason == null) { "native execution must not have a fallback reason" }

            GraphAlgorithmExecutionPath.JVM_FALLBACK ->
                require(fallbackReason != null) { "JVM fallback execution must have a fallback reason" }
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        private val PROVIDER_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        /** upstream 실행 결과를 안전한 workshop 도메인 모델로 투영합니다. */
        fun from(execution: GraphAlgorithmExecution): AbuserAlgorithmExecution =
            AbuserAlgorithmExecution(
                algorithm = execution.algorithm,
                providerId = execution.providerId,
                path = execution.path,
                fallbackReason = execution.fallbackReason,
            )
    }
}
