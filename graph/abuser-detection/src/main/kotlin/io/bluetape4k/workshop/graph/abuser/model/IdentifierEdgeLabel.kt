package io.bluetape4k.workshop.graph.abuser.model

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * 어뷰저 클러스터 감지에서 사용하는 사용자-식별자 간선 유형의 도메인 수준 dispatch enum입니다.
 *
 * 이 enum은 스키마 선언이 아닙니다([io.bluetape4k.workshop.graph.abuser.schema] 참조). 원시 간선 레이블 문자열을 감싼 typed wrapper로, 서비스 계층 dispatch가 타입 안전하고
 * 누락 없이 처리되게 합니다.
 *
 * ## 동작 / 계약
 * - [all]은 모든 식별자 간선 레이블의 권위 있는 순서 목록입니다.
 * - [REFERRED_BY]는 의도적으로 [all]에서 제외됩니다. 추천 관계만으로는 공유
 *   신원을 뜻하지 않으므로 어뷰저 클러스터 BFS 순회에 참여하면 안 됩니다.
 *
 * ## 사용 예
 * ```kotlin
 * IdentifierEdgeLabel.all.forEach { lbl ->
 *     val neighbors = ops.neighbors(userId, NeighborOptions(lbl.value, OUTGOING, 1))
 * }
 * ```
 */
@JvmInline
value class IdentifierEdgeLabel(val value: String): Serializable {

    init {
        value.requireNotBlank("value")
    }

    companion object {
        private const val serialVersionUID = 1L

        /** User 정점을 Device 정점에 연결합니다. */
        val USES_DEVICE = IdentifierEdgeLabel("USES_DEVICE")

        /** User 정점을 IpAddress 정점에 연결합니다. */
        val USES_IP = IdentifierEdgeLabel("USES_IP")

        /** User 정점을 PhoneNumber 정점에 연결합니다. */
        val HAS_PHONE = IdentifierEdgeLabel("HAS_PHONE")

        /** User 정점을 PaymentMethod 정점에 연결합니다. */
        val USES_PAYMENT = IdentifierEdgeLabel("USES_PAYMENT")

        /**
         * 어뷰저 클러스터 순회에 사용하는 모든 식별자 간선 레이블입니다.
         * REFERRED_BY는 제외합니다. 추천 관계만으로는 공유 신원을 뜻하지 않습니다.
         */
        val all: List<IdentifierEdgeLabel> = listOf(USES_DEVICE, USES_IP, HAS_PHONE, USES_PAYMENT)
    }
}
