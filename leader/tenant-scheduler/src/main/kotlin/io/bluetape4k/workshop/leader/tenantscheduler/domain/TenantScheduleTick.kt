package io.bluetape4k.workshop.leader.tenantscheduler.domain

import io.bluetape4k.support.requireNotEmpty
import java.io.Serializable

/**
 * logical scheduler tick 하나에 대한 결정적 입력이다.
 */
data class TenantScheduleTick(
    val tick: TenantLogicalTick,
    val candidateNodes: List<TenantNodeId>,
    val dueTenants: List<TenantId> = emptyList(),
    val actionFailures: List<TenantId> = emptyList(),
    val initialLeases: List<TenantLeaseState> = emptyList(),
): Serializable {

    init {
        candidateNodes.requireNotEmpty("candidateNodes")
        requireDistinct(candidateNodes.map { it.value }, "candidateNodes")
        requireDistinct(dueTenants.map { it.value }, "dueTenants")
        requireDistinct(actionFailures.map { it.value }, "actionFailures")
        requireDistinct(initialLeases.map { it.tenantId.value }, "initialLeases")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
