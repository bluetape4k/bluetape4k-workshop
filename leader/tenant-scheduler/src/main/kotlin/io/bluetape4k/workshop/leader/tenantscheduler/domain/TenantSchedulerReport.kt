package io.bluetape4k.workshop.leader.tenantscheduler.domain

import java.io.Serializable

/**
 * Immutable report returned by [io.bluetape4k.workshop.leader.tenantscheduler.service.TenantSchedulerLab].
 */
data class TenantSchedulerReport(
    val eventRows: List<TenantSchedulerEventRow>,
    val finalLeases: Map<TenantId, TenantLeaseState>,
    val selectedTenants: List<TenantId>,
    val truncated: Boolean,
    val droppedEventRows: Int,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Learner-visible event emitted for one selected tenant.
 */
data class TenantSchedulerEventRow(
    val tick: TenantLogicalTick,
    val tenantId: TenantId,
    val nodeId: TenantNodeId,
    val lockName: String,
    val outcome: TenantRunOutcome,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
