package io.bluetape4k.workshop.leader.tenantscheduler.domain

import io.bluetape4k.support.requireInRange
import java.io.Serializable

/**
 * tenant lease 하나에 대한 이름 붙은 lease timestamp 묶음이다.
 *
 * 세 개의 logical tick을 함께 묶어 공개 API에서 위치 기반 실수를 피한다.
 */
data class TenantLeaseWindow(
    val acquiredAt: TenantLogicalTick,
    val renewedAt: TenantLogicalTick,
    val expiresAt: TenantLogicalTick,
): Serializable {

    init {
        acquiredAt.compareTo(renewedAt).requireInRange(Int.MIN_VALUE, 0, "acquiredAt.compareTo(renewedAt)")
        renewedAt.compareTo(expiresAt).requireInRange(Int.MIN_VALUE, -1, "renewedAt.compareTo(expiresAt)")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
