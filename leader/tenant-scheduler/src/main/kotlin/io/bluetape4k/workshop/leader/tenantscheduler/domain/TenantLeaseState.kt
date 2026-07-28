package io.bluetape4k.workshop.leader.tenantscheduler.domain

import java.io.Serializable

/**
 * tenant-local scheduled job 하나의 lease 상태이다.
 */
data class TenantLeaseState(
    val tenantId: TenantId,
    val lockName: String,
    val ownerNodeId: TenantNodeId,
    val window: TenantLeaseWindow,
    val lastOutcome: TenantRunOutcome,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
