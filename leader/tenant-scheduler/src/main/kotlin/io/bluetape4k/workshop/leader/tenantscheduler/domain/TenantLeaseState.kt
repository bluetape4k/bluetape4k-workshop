package io.bluetape4k.workshop.leader.tenantscheduler.domain

import java.io.Serializable

/**
 * Lease state for one tenant-local scheduled job.
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
