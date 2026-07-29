package io.bluetape4k.workshop.leader.tenantscheduler.domain

import java.io.Serializable

/**
 * [io.bluetape4k.workshop.leader.tenantscheduler.service.TenantSchedulerLab]가 반환하는 불변 report이다.
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
 * 선택된 tenant 하나에 대해 학습자에게 보여 주는 event이다.
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
