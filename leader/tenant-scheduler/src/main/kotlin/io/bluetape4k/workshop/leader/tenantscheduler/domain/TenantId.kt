package io.bluetape4k.workshop.leader.tenantscheduler.domain

import java.io.Serializable

/**
 * Non-sensitive tenant alias used in lock names, reports, and metric examples.
 *
 * The value is canonicalized to a lowercase metric/log-safe alias. Customer
 * names, emails, account ids, and other sensitive identifiers must be mapped to
 * a stable alias before constructing this value.
 */
@ConsistentCopyVisibility
data class TenantId private constructor(
    val value: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * Creates a tenant alias after validating and canonicalizing caller
         * input.
         */
        operator fun invoke(raw: String): TenantId =
            TenantId(normalizeTenantAlias(raw, "tenantId"))
    }

    override fun toString(): String = value
}
