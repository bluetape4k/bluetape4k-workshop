package io.bluetape4k.workshop.leader.tenantscheduler.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantId
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantJobName
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantLogicalTick
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantNodeId
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantRunOutcome
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantSchedulePolicy
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantScheduleTick
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantSchedulerReadmeSnippetTest {

    @Test
    fun `readme tenant scheduler snippet stays executable`() {
        val tenantA = TenantId("tenant-a")
        val tenantB = TenantId("tenant-b")
        val nodeA = TenantNodeId("node-a")

        val policy = TenantSchedulePolicy(
            jobName = TenantJobName("invoice-sync"),
            tenants = listOf(tenantA, tenantB),
            staleAfterTicks = 2,
            maxTenantTagValues = 2,
        )

        val report = TenantSchedulerLab().run(
            policy = policy,
            ticks = listOf(
                TenantScheduleTick(
                    tick = TenantLogicalTick(0),
                    candidateNodes = listOf(nodeA),
                    actionFailures = listOf(tenantB),
                ),
            ),
        )

        report.eventRows.map { it.outcome } shouldBeEqualTo listOf(
            TenantRunOutcome.EXECUTED,
            TenantRunOutcome.FAILED,
        )

        val lockName = TenantLockNamePlanner().lockName(tenantA, policy.jobName)
        lockName shouldBeEqualTo "tenant:tenant-a:invoice-sync"

        val tags = TenantMetricTagPolicy(maxTenantTagValues = policy.maxTenantTagValues)
            .decide(policy.tenants)

        tags.cardinalityLimited.shouldBeFalse()
        tags.metricRows.map { it.tags } shouldBeEqualTo listOf(
            mapOf("tenant" to "tenant-a"),
            mapOf("tenant" to "tenant-b"),
        )
    }
}
