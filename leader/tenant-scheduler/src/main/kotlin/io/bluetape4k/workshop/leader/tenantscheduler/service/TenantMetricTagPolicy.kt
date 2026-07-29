package io.bluetape4k.workshop.leader.tenantscheduler.service

import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantId
import java.io.Serializable
import kotlin.math.min

/**
 * 워크숍 report가 tenant별 metric tag를 노출해도 되는지 결정한다.
 *
 * local lab은 작고 제한된 tenant 집합을 보여 줄 수 있다.
 * 하지만 요청 cardinality나 실제 cardinality가 안전한 예제 범위를 넘으면 의도적으로 `tenant=bounded`로 낮춘다.
 */
data class TenantMetricTagPolicy(
    val maxTenantTagValues: Int = DEFAULT_MAX_TENANT_TAG_VALUES,
): Serializable {

    init {
        maxTenantTagValues.requirePositiveNumber("maxTenantTagValues")
    }

    /**
     * 전달된 tenant 집합에 대해 제한된 metric-tag row를 만든다.
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
 * tenant 집합에 [TenantMetricTagPolicy]를 적용한 결과이다.
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
 * README 예제와 결정적 테스트에서 사용하는 제한된 metric tag row이다.
 */
data class TenantMetricRow(
    val tags: Map<String, String>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
