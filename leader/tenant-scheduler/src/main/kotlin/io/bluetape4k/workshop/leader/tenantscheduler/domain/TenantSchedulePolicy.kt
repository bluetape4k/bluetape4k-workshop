package io.bluetape4k.workshop.leader.tenantscheduler.domain

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * Immutable configuration for a deterministic tenant scheduler scenario.
 */
data class TenantSchedulePolicy(
    val jobName: TenantJobName,
    val tenants: List<TenantId>,
    val maxTenantsPerTick: Int = tenants.size,
    val staleAfterTicks: Int = 2,
    val maxTenantTagValues: Int = DEFAULT_MAX_TENANT_TAG_VALUES,
    val eventHistoryLimit: Int = DEFAULT_EVENT_HISTORY_LIMIT,
): Serializable {

    init {
        tenants.requireNotEmpty("tenants")
        requireDistinct(tenants.map { it.value }, "tenants")
        maxTenantsPerTick.requireInRange(1, tenants.size, "maxTenantsPerTick")
        staleAfterTicks.requirePositiveNumber("staleAfterTicks")
        maxTenantTagValues.requirePositiveNumber("maxTenantTagValues")
        eventHistoryLimit.requireInRange(1, MAX_EVENT_HISTORY_LIMIT, "eventHistoryLimit")
    }

    companion object {
        const val DEFAULT_MAX_TENANT_TAG_VALUES: Int = 16
        const val DEFAULT_EVENT_HISTORY_LIMIT: Int = 64
        const val MAX_EVENT_HISTORY_LIMIT: Int = 512
        private const val serialVersionUID: Long = 1L
    }
}

internal fun requireDistinct(values: List<String>, fieldName: String) {
    (values.size - values.distinct().size).requireInRange(0, 0, "$fieldName.duplicateCount")
}
