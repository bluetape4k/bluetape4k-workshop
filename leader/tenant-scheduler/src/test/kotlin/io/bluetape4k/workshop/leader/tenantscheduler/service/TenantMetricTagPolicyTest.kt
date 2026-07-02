package io.bluetape4k.workshop.leader.tenantscheduler.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantMetricTagPolicyTest {

    @Test
    fun `emits per tenant tags while tenant count stays inside local bound`() {
        val decision = TenantMetricTagPolicy(maxTenantTagValues = 2)
            .decide(listOf(TenantId("Tenant-A"), TenantId("Tenant-B")))

        decision.cardinalityLimited.shouldBeFalse()
        decision.metricRows.map { it.tags } shouldBeEqualTo listOf(
            mapOf("tenant" to "tenant-a"),
            mapOf("tenant" to "tenant-b"),
        )
        decision.metricRows.flatMap { it.tags.keys }.toSet() shouldBeEqualTo setOf("tenant")
    }

    @Test
    fun `suppresses per tenant tags when tenant count exceeds configured bound`() {
        val decision = TenantMetricTagPolicy(maxTenantTagValues = 1)
            .decide(listOf(TenantId("Tenant-A"), TenantId("Tenant-B")))

        decision.cardinalityLimited.shouldBeTrue()
        decision.actualTenantCount shouldBeEqualTo 2
        decision.effectiveTenantTagLimit shouldBeEqualTo 1
        decision.metricRows.map { it.tags } shouldBeEqualTo listOf(
            mapOf("tenant" to "bounded"),
        )
    }

    @Test
    fun `hard cap prevents huge requested tenant tag limits from producing unbounded rows`() {
        val tenants = (1..10_000).map { TenantId("tenant-$it") }

        val decision = TenantMetricTagPolicy(maxTenantTagValues = 10_000)
            .decide(tenants)

        decision.cardinalityLimited.shouldBeTrue()
        decision.actualTenantCount shouldBeEqualTo 10_000
        decision.effectiveTenantTagLimit shouldBeEqualTo TenantMetricTagPolicy.MAX_LOCAL_TENANT_TAG_VALUES
        decision.metricRows.map { it.tags } shouldBeEqualTo listOf(
            mapOf("tenant" to "bounded"),
        )
    }
}
