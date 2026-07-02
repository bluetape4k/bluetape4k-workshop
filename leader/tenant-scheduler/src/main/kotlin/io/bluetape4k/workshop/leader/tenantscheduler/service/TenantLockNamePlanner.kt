package io.bluetape4k.workshop.leader.tenantscheduler.service

import io.bluetape4k.leader.TenantLockNamespace
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantId
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantJobName

/**
 * Derives backend lock names from tenant-local workshop inputs.
 *
 * The planner delegates namespace formatting and final lock-name validation to
 * `TenantLockNamespace` so the workshop uses the same rule as real leader
 * electors.
 */
class TenantLockNamePlanner {

    /**
     * Returns the backend lock name for a tenant-local scheduled job.
     */
    fun lockName(
        tenantId: TenantId,
        jobName: TenantJobName,
    ): String =
        TenantLockNamespace(tenantId.value).lockName(jobName.value)
}
