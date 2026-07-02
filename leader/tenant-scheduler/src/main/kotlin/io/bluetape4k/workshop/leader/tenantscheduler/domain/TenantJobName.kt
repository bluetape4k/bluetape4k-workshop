package io.bluetape4k.workshop.leader.tenantscheduler.domain

import java.io.Serializable

/**
 * Tenant-local scheduled job name used before tenant namespace expansion.
 *
 * The backend lock name must be derived through `TenantLockNamespace`, not by
 * manual string concatenation.
 */
@ConsistentCopyVisibility
data class TenantJobName private constructor(
    val value: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * Creates a safe tenant-local job name.
         */
        operator fun invoke(raw: String): TenantJobName =
            TenantJobName(normalizeTenantAlias(raw, "jobName"))
    }

    override fun toString(): String = value
}
