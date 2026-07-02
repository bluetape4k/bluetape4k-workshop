package io.bluetape4k.workshop.leader.tenantscheduler.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantId
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantJobName
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantLeaseState
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantLeaseWindow
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantLogicalTick
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantNodeId
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantRunOutcome
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantSchedulePolicy
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantScheduleTick
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantSchedulerLabTest {

    private val tenantA = TenantId("tenant-a")
    private val tenantB = TenantId("tenant-b")
    private val tenantC = TenantId("tenant-c")
    private val nodeA = TenantNodeId("node-a")
    private val nodeB = TenantNodeId("node-b")
    private val jobName = TenantJobName("invoice-sync")
    private val lab = TenantSchedulerLab()

    @Test
    fun `two tenants run independently in one tick`() {
        val report = lab.run(
            policy = policy(tenantA, tenantB),
            ticks = listOf(tick(0, nodeA)),
        )

        report.eventRows.map { it.tenantId } shouldBeEqualTo listOf(tenantA, tenantB)
        report.eventRows.map { it.outcome } shouldBeEqualTo listOf(
            TenantRunOutcome.EXECUTED,
            TenantRunOutcome.EXECUTED,
        )
        report.finalLeases.mapValues { it.value.ownerNodeId } shouldBeEqualTo mapOf(
            tenantA to nodeA,
            tenantB to nodeA,
        )
    }

    @Test
    fun `one tenant failure does not block another tenant`() {
        val report = lab.run(
            policy = policy(tenantA, tenantB),
            ticks = listOf(
                tick(
                    value = 0,
                    nodeA,
                    actionFailures = listOf(tenantA),
                ),
            ),
        )

        report.eventRows.map { it.tenantId } shouldBeEqualTo listOf(tenantA, tenantB)
        report.eventRows.map { it.outcome } shouldBeEqualTo listOf(
            TenantRunOutcome.FAILED,
            TenantRunOutcome.EXECUTED,
        )
    }

    @Test
    fun `stale lease does not hand off before expiry and hands off at boundary`() {
        val staleLease = lease(
            tenantId = tenantA,
            ownerNodeId = nodeA,
            acquiredAt = 0,
            renewedAt = 0,
            expiresAt = 2,
        )

        val report = lab.run(
            policy = policy(tenantA),
            ticks = listOf(
                tick(1, nodeB, initialLeases = listOf(staleLease)),
                tick(2, nodeB),
            ),
        )

        report.eventRows.map { it.outcome } shouldBeEqualTo listOf(
            TenantRunOutcome.SKIPPED,
            TenantRunOutcome.STALE_HANDOFF,
        )
        report.finalLeases[tenantA]?.ownerNodeId shouldBeEqualTo nodeB
    }

    @Test
    fun `stale handoff does not disturb unrelated tenant lease`() {
        val staleLease = lease(
            tenantId = tenantA,
            ownerNodeId = nodeA,
            acquiredAt = 0,
            renewedAt = 0,
            expiresAt = 2,
        )
        val activeLease = lease(
            tenantId = tenantB,
            ownerNodeId = nodeB,
            acquiredAt = 1,
            renewedAt = 1,
            expiresAt = 4,
        )

        val report = lab.run(
            policy = policy(tenantA, tenantB),
            ticks = listOf(
                tick(
                    value = 2,
                    nodeB,
                    initialLeases = listOf(staleLease, activeLease),
                ),
            ),
        )

        report.eventRows.map { it.tenantId } shouldBeEqualTo listOf(tenantA, tenantB)
        report.eventRows.map { it.outcome } shouldBeEqualTo listOf(
            TenantRunOutcome.STALE_HANDOFF,
            TenantRunOutcome.EXECUTED,
        )
        report.finalLeases[tenantA]?.ownerNodeId shouldBeEqualTo nodeB
        report.finalLeases[tenantB]?.ownerNodeId shouldBeEqualTo nodeB
        report.finalLeases[tenantB]?.window?.acquiredAt shouldBeEqualTo activeLease.window.acquiredAt
    }

    @Test
    fun `failed action retains active lease until expiry`() {
        val activeLease = lease(
            tenantId = tenantA,
            ownerNodeId = nodeA,
            acquiredAt = 0,
            renewedAt = 0,
            expiresAt = 2,
        )

        val report = lab.run(
            policy = policy(tenantA),
            ticks = listOf(
                tick(1, nodeA, actionFailures = listOf(tenantA), initialLeases = listOf(activeLease)),
                tick(2, nodeB),
            ),
        )

        report.eventRows.map { it.outcome } shouldBeEqualTo listOf(
            TenantRunOutcome.FAILED,
            TenantRunOutcome.STALE_HANDOFF,
        )
        report.finalLeases[tenantA]?.ownerNodeId shouldBeEqualTo nodeB
    }

    @Test
    fun `bounded capacity rotates tenants by last selected tick`() {
        val report = lab.run(
            policy = policy(tenantA, tenantB, tenantC, maxTenantsPerTick = 1),
            ticks = listOf(tick(0, nodeA), tick(1, nodeA), tick(2, nodeA)),
        )

        report.eventRows.map { it.tenantId } shouldBeEqualTo listOf(tenantA, tenantB, tenantC)
    }

    @Test
    fun `same input and separate runs produce the same report`() {
        val policy = policy(tenantA, tenantB)
        val ticks = listOf(tick(0, nodeA), tick(1, nodeA))

        val first = lab.run(policy, ticks)
        val second = TenantSchedulerLab().run(policy, ticks)

        second shouldBeEqualTo first
    }

    @Test
    fun `policy and tick reject ambiguous duplicate input after canonicalization`() {
        assertFailsWith<IllegalArgumentException> {
            policy(TenantId("Tenant-A"), TenantId("tenant-a"))
        }
        assertFailsWith<IllegalArgumentException> {
            tick(0, nodeA, dueTenants = listOf(TenantId("Tenant-A"), TenantId("tenant-a")))
        }
        assertFailsWith<IllegalArgumentException> {
            tick(0, TenantNodeId("Node-A"), TenantNodeId("node-a"))
        }
        assertFailsWith<IllegalArgumentException> {
            tick(0, nodeA, actionFailures = listOf(TenantId("Tenant-A"), TenantId("tenant-a")))
        }
        assertFailsWith<IllegalArgumentException> {
            tick(
                value = 0,
                nodeA,
                initialLeases = listOf(
                    lease(tenantA, nodeA, acquiredAt = 0, renewedAt = 0, expiresAt = 2),
                    lease(TenantId("Tenant-A"), nodeB, acquiredAt = 0, renewedAt = 0, expiresAt = 2),
                ),
            )
        }
    }

    @Test
    fun `initial lease must use tenant scoped lock name for policy job`() {
        val leaseWithWrongLockName = lease(
            tenantId = tenantA,
            ownerNodeId = nodeA,
            acquiredAt = 0,
            renewedAt = 0,
            expiresAt = 2,
        ).copy(lockName = "tenant:tenant-a:other-job")

        assertFailsWith<IllegalArgumentException> {
            lab.run(
                policy = policy(tenantA),
                ticks = listOf(tick(1, nodeB, initialLeases = listOf(leaseWithWrongLockName))),
            )
        }
    }

    @Test
    fun `stress scenario remains bounded and rotates without starvation`() {
        val tenants = (1..50).map { TenantId("tenant-$it") }
        val ticks = (0..4).map { tick(it, nodeA) }

        val report = lab.run(
            policy = TenantSchedulePolicy(
                jobName = jobName,
                tenants = tenants,
                maxTenantsPerTick = 10,
                staleAfterTicks = 2,
                eventHistoryLimit = 25,
            ),
            ticks = ticks,
        )

        report.eventRows.size shouldBeEqualTo 25
        report.truncated.shouldBeTrue()
        report.droppedEventRows shouldBeEqualTo 25
        report.selectedTenants.toSet() shouldBeEqualTo tenants.toSet()
    }

    private fun policy(
        vararg tenants: TenantId,
        maxTenantsPerTick: Int = tenants.size,
    ): TenantSchedulePolicy =
        TenantSchedulePolicy(
            jobName = jobName,
            tenants = tenants.toList(),
            maxTenantsPerTick = maxTenantsPerTick,
            staleAfterTicks = 2,
        )

    private fun tick(
        value: Int,
        vararg candidateNodes: TenantNodeId,
        dueTenants: List<TenantId> = emptyList(),
        actionFailures: List<TenantId> = emptyList(),
        initialLeases: List<TenantLeaseState> = emptyList(),
    ): TenantScheduleTick =
        TenantScheduleTick(
            tick = TenantLogicalTick(value),
            candidateNodes = candidateNodes.toList(),
            dueTenants = dueTenants,
            actionFailures = actionFailures,
            initialLeases = initialLeases,
        )

    private fun lease(
        tenantId: TenantId,
        ownerNodeId: TenantNodeId,
        acquiredAt: Int,
        renewedAt: Int,
        expiresAt: Int,
    ): TenantLeaseState =
        TenantLeaseState(
            tenantId = tenantId,
            lockName = TenantLockNamePlanner().lockName(tenantId, jobName),
            ownerNodeId = ownerNodeId,
            window = TenantLeaseWindow(
                acquiredAt = TenantLogicalTick(acquiredAt),
                renewedAt = TenantLogicalTick(renewedAt),
                expiresAt = TenantLogicalTick(expiresAt),
            ),
            lastOutcome = TenantRunOutcome.EXECUTED,
        )
}
