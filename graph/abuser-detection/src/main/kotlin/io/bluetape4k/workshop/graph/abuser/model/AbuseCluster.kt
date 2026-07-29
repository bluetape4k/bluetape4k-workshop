package io.bluetape4k.workshop.graph.abuser.model

import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphVertex
import java.io.Serializable

/**
 * 하나 이상의 식별자(device, IP, phone, payment)를 공유하는 사용자 정점 클러스터입니다.
 *
 * ## 동작 / 계약
 * - [seedUserId]는 식별자 그래프 순회를 시작한 사용자입니다.
 * - [users]는 공유 식별자 정점을 통해 도달 가능한 다른 사용자를 모두 담습니다. seed 사용자는
 *   이 목록에서 제외됩니다.
 * - [sharedIdentifiers]는 클러스터를 연결하는 식별자 정점(Device, IpAddress, PhoneNumber, PaymentMethod)입니다.
 *   클러스터를 서로 연결합니다.
 * - [users] 목록이 비어 있으면 seed 사용자에게 공유 식별자 이웃이 없고 어떤 어뷰저 클러스터에도
 *   속하지 않는다는 뜻입니다.
 *
 * ## 사용 예
 * ```kotlin
 * val cluster = service.findAbuseCluster(userId)
 * if (cluster.users.isNotEmpty()) {
 *     log.warn { "User $userId is part of an abuse cluster with ${cluster.users.size} others" }
 * }
 * ```
 */
data class AbuseCluster(
    /** 이웃을 순회한 seed 사용자 ID입니다. */
    val seedUserId: GraphElementId,
    /** seed 사용자와 하나 이상의 식별자를 공유하는 다른 사용자입니다. */
    val users: List<GraphVertex>,
    /** 클러스터를 연결하는 식별자 정점입니다. */
    val sharedIdentifiers: List<GraphVertex>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
