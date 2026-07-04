package io.bluetape4k.workshop.leader.tenantscheduler.domain

import io.bluetape4k.support.requireInRange
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
        acquiredAt.compareTo(renewedAt).requireInRange(Int.MIN_VALUE, 0, "acquiredAt.compareTo(renewedAt)")
        renewedAt.compareTo(expiresAt).requireInRange(Int.MIN_VALUE, -1, "renewedAt.compareTo(expiresAt)")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
