package io.bluetape4k.workshop.leader.tenantscheduler.domain

import java.io.Serializable

/**
 * Synthetic scheduler node alias used by deterministic workshop scenarios.
 *
 * Node ids are examples only and follow the same safe alias policy as tenant
 * ids so diagrams, reports, and tests never expose infrastructure identifiers.
 */
@ConsistentCopyVisibility
data class TenantNodeId private constructor(
    val value: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * Creates a safe synthetic node alias.
         */
        operator fun invoke(raw: String): TenantNodeId =
            TenantNodeId(normalizeTenantAlias(raw, "nodeId"))
    }

    override fun toString(): String = value
}
