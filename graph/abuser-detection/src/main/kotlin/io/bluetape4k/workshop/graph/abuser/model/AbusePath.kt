package io.bluetape4k.workshop.graph.abuser.model

import io.bluetape4k.graph.model.GraphElementId
import java.io.Serializable

/**
 * 사용자와 그 식별자 정점 하나 사이의 단일 식별자 공유 경로입니다.
 *
 * ## 동작 / 계약
 * - [identifierVertexId]는 공유 식별자 정점(Device, IpAddress, PhoneNumber, PaymentMethod)의 ID입니다.
 * - [edgeLabel]은 사용자를 식별자에 연결하는 관계 유형을 식별합니다.
 * - 호출자는 여러 [AbusePath] 결과를 [edgeLabel]로 묶어 의심 사용자를 공유 자원에 연결하는
 *   모든 식별자 유형을 나열할 수 있습니다.
 *
 * ## 사용 예
 * ```kotlin
 * val paths = service.explainSuspicion(userId)
 * val devicePaths = paths.filter { it.edgeLabel == IdentifierEdgeLabel.USES_DEVICE }
 * ```
 */
data class AbusePath(
    /** 공유 식별자 정점(Device, IpAddress, PhoneNumber, PaymentMethod)의 ID입니다. */
    val identifierVertexId: GraphElementId,
    /** 사용자가 이 식별자에 어떻게 연결되는지 설명하는 간선 레이블 유형입니다. */
    val edgeLabel: IdentifierEdgeLabel,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
