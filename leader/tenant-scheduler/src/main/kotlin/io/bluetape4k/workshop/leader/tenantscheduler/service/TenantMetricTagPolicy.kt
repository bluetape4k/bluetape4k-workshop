package io.bluetape4k.workshop.leader.tenantscheduler.service

import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantId
import java.io.Serializable
import kotlin.math.min

/**
 * Decides whether a workshop report may expose per-tenant metric tags.
 *
 * The local lab may show small bounded tenant sets, but it intentionally
 * degrades to `tenant=bounded` when the requested or actual cardinality is too
 * high for a safe example.
 */
data class TenantMetricTagPolicy(
    val maxTenantTagValues: Int = DEFAULT_MAX_TENANT_TAG_VALUES,
): Serializable {

    init {
        maxTenantTagValues.requirePositiveNumber("maxTenantTagValues")
    }

    /**
     * Builds bounded metric-tag rows for the supplied tenant set.
     */
    fun decide(tenants: Collection<TenantId>): TenantMetricTagDecision {
        val distinctTenants = tenants.distinctBy { it.value }
        val effectiveLimit = min(maxTenantTagValues, MAX_LOCAL_TENANT_TAG_VALUES)
        val usePerTenantRows = maxTenantTagValues <= MAX_LOCAL_TENANT_TAG_VALUES &&
            distinctTenants.size <= effectiveLimit

        val rows = if (usePerTenantRows) {
            distinctTenants.map { tenant ->
                TenantMetricRow(tags = mapOf("tenant" to tenant.value))
            }
        } else {
            listOf(TenantMetricRow(tags = mapOf("tenant" to "bounded")))
        }

        return TenantMetricTagDecision(
            cardinalityLimited = !usePerTenantRows,
            actualTenantCount = distinctTenants.size,
            effectiveTenantTagLimit = effectiveLimit,
            metricRows = rows,
        )
    }

    companion object {
        const val DEFAULT_MAX_TENANT_TAG_VALUES: Int = 16
        const val MAX_LOCAL_TENANT_TAG_VALUES: Int = 100
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Result of applying [TenantMetricTagPolicy] to a tenant set.
 */
data class TenantMetricTagDecision(
    val cardinalityLimited: Boolean,
    val actualTenantCount: Int,
    val effectiveTenantTagLimit: Int,
    val metricRows: List<TenantMetricRow>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Bounded metric tag row used by README examples and deterministic tests.
 */
data class TenantMetricRow(
    val tags: Map<String, String>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
