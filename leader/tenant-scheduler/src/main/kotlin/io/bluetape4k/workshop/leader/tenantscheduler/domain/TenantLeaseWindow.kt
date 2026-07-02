package io.bluetape4k.workshop.leader.tenantscheduler.domain

import java.io.Serializable

/**
 * Named lease timestamps for one tenant lease.
 *
 * Grouping the three logical ticks avoids positional mistakes in public APIs.
 */
data class TenantLeaseWindow(
    val acquiredAt: TenantLogicalTick,
    val renewedAt: TenantLogicalTick,
    val expiresAt: TenantLogicalTick,
): Serializable {

    init {
        require(acquiredAt <= renewedAt) {
            "acquiredAt must be before or equal to renewedAt"
        }
        require(renewedAt < expiresAt) {
            "expiresAt must be after renewedAt"
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
