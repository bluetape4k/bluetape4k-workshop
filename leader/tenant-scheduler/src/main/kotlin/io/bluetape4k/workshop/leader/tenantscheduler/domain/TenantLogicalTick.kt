package io.bluetape4k.workshop.leader.tenantscheduler.domain

import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.io.Serializable

/**
 * Logical scheduler tick used by the deterministic lab.
 *
 * The lab never reads wall-clock time; every lease transition is derived from
 * this explicit tick value.
 */
@ConsistentCopyVisibility
data class TenantLogicalTick private constructor(
    val value: Int,
): Comparable<TenantLogicalTick>, Serializable {

    override fun compareTo(other: TenantLogicalTick): Int =
        value.compareTo(other.value)

    operator fun plus(delta: Int): TenantLogicalTick {
        delta.requireZeroOrPositiveNumber("delta")
        return TenantLogicalTick(value + delta)
    }

    companion object {
        val MIN: TenantLogicalTick = TenantLogicalTick(Int.MIN_VALUE)

        private const val serialVersionUID: Long = 1L

        /**
         * Creates a non-negative logical tick.
         */
        operator fun invoke(value: Int): TenantLogicalTick {
            value.requireZeroOrPositiveNumber("tick")
            return TenantLogicalTick(value)
        }
    }
}
