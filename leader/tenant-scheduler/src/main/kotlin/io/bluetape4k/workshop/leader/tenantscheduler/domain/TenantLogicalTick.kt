package io.bluetape4k.workshop.leader.tenantscheduler.domain

import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.io.Serializable

/**
 * 결정적 lab에서 사용하는 논리 scheduler tick이다.
 *
 * lab은 wall-clock 시간을 읽지 않는다. 모든 lease 전이는 이 명시적 tick 값에서 도출된다.
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
         * 음수가 아닌 logical tick을 만든다.
         */
        operator fun invoke(value: Int): TenantLogicalTick {
            value.requireZeroOrPositiveNumber("tick")
            return TenantLogicalTick(value)
        }
    }
}
