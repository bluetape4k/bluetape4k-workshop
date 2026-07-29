package io.bluetape4k.workshop.leader.tenantscheduler.domain

import java.io.Serializable

/**
 * 결정적 워크숍 scenario에서 사용하는 합성 scheduler node alias이다.
 *
 * node id는 예제 전용이며 tenant id와 같은 안전한 alias 정책을 따른다.
 * 따라서 diagram, report, test에서 infrastructure 식별자가 노출되지 않는다.
 */
@ConsistentCopyVisibility
data class TenantNodeId private constructor(
    val value: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * 안전한 합성 node alias를 만든다.
         */
        operator fun invoke(raw: String): TenantNodeId =
            TenantNodeId(normalizeTenantAlias(raw, "nodeId"))
    }

    override fun toString(): String = value
}
