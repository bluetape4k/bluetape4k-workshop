package io.bluetape4k.workshop.leader.tenantscheduler.service

import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantId
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantLeaseState
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantLeaseWindow
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantLogicalTick
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantNodeId
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantRunOutcome
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantSchedulePolicy
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantScheduleTick
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantSchedulerEventRow
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantSchedulerReport

/**
 * Pure logical-tick reducer for tenant-scoped leader scheduling scenarios.
 *
 * This lab does not acquire a distributed lock. It models the observable
 * scheduling contract learners need before moving to real leader backends.
 */
class TenantSchedulerLab(
    private val lockNamePlanner: TenantLockNamePlanner = TenantLockNamePlanner(),
) {

    /**
     * Runs the supplied finite scenario and returns a new immutable report.
     */
    fun run(
        policy: TenantSchedulePolicy,
        ticks: List<TenantScheduleTick>,
    ): TenantSchedulerReport {
        val knownTenants = policy.tenants.toSet()
        val leases = linkedMapOf<TenantId, TenantLeaseState>()
        val lastSelectedTicks = policy.tenants.associateWith { TenantLogicalTick.MIN }.toMutableMap()
        val selectedTenants = mutableListOf<TenantId>()
        val eventRows = mutableListOf<TenantSchedulerEventRow>()
        var droppedEventRows = 0

        ticks.forEach { tick ->
            tick.initialLeases.forEach { lease ->
                lease.tenantId.requireKnownTenant(knownTenants, "initialLeases.tenantId")
                (lease.lockName == lockNamePlanner.lockName(lease.tenantId, policy.jobName))
                    .toMissingCount()
                    .requireInRange(0, 0, "initialLeases.lockName")
                leases[lease.tenantId] = lease
            }

            val dueTenants = if (tick.dueTenants.isEmpty()) {
                policy.tenants
            } else {
                tick.dueTenants
            }
            dueTenants.requireKnownTenants(knownTenants, "dueTenants")
            tick.actionFailures.requireKnownTenants(knownTenants, "actionFailures")

            dueTenants
                .sortedWith(compareBy<TenantId> { lastSelectedTicks.getValue(it).value }.thenBy { it.value })
                .take(policy.maxTenantsPerTick)
                .forEach { tenantId ->
                    val event = reduceTenant(policy, tick, tenantId, leases)
                    selectedTenants += tenantId
                    lastSelectedTicks[tenantId] = tick.tick

                    if (eventRows.size < policy.eventHistoryLimit) {
                        eventRows += event
                    } else {
                        droppedEventRows++
                    }
                }
        }

        return TenantSchedulerReport(
            eventRows = eventRows.toList(),
            finalLeases = leases.toMap(),
            selectedTenants = selectedTenants.toList(),
            truncated = droppedEventRows > 0,
            droppedEventRows = droppedEventRows,
        )
    }

    private fun reduceTenant(
        policy: TenantSchedulePolicy,
        tick: TenantScheduleTick,
        tenantId: TenantId,
        leases: MutableMap<TenantId, TenantLeaseState>,
    ): TenantSchedulerEventRow {
        val currentLease = leases[tenantId]
        val activeLease = currentLease?.takeIf { tick.tick < it.window.expiresAt }
        val firstCandidate = tick.candidateNodes.first()
        val actionFails = tenantId in tick.actionFailures

        val outcomeAndLease = when {
            activeLease == null -> acquireLease(policy, tick, tenantId, firstCandidate, actionFails, currentLease != null)
            activeLease.ownerNodeId in tick.candidateNodes && actionFails -> TenantRunOutcome.FAILED to activeLease
            activeLease.ownerNodeId in tick.candidateNodes -> renewLease(policy, tick.tick, activeLease)
            else -> TenantRunOutcome.SKIPPED to activeLease
        }

        val (outcome, nextLease) = outcomeAndLease
        leases[tenantId] = nextLease

        return TenantSchedulerEventRow(
            tick = tick.tick,
            tenantId = tenantId,
            nodeId = nextLease.ownerNodeId,
            lockName = nextLease.lockName,
            outcome = outcome,
        )
    }

    private fun acquireLease(
        policy: TenantSchedulePolicy,
        tick: TenantScheduleTick,
        tenantId: TenantId,
        nodeId: TenantNodeId,
        actionFails: Boolean,
        wasExpired: Boolean,
    ): Pair<TenantRunOutcome, TenantLeaseState> {
        val outcome = when {
            actionFails -> TenantRunOutcome.FAILED
            wasExpired -> TenantRunOutcome.STALE_HANDOFF
            else -> TenantRunOutcome.EXECUTED
        }

        return outcome to TenantLeaseState(
            tenantId = tenantId,
            lockName = lockNamePlanner.lockName(tenantId, policy.jobName),
            ownerNodeId = nodeId,
            window = TenantLeaseWindow(
                acquiredAt = tick.tick,
                renewedAt = tick.tick,
                expiresAt = tick.tick + policy.staleAfterTicks,
            ),
            lastOutcome = outcome,
        )
    }

    private fun renewLease(
        policy: TenantSchedulePolicy,
        currentTick: TenantLogicalTick,
        lease: TenantLeaseState,
    ): Pair<TenantRunOutcome, TenantLeaseState> {
        val outcome = TenantRunOutcome.EXECUTED
        return outcome to lease.copy(
            window = TenantLeaseWindow(
                acquiredAt = lease.window.acquiredAt,
                renewedAt = currentTick,
                expiresAt = currentTick + policy.staleAfterTicks,
            ),
            lastOutcome = outcome,
        )
    }
}

private fun TenantId.requireKnownTenant(knownTenants: Set<TenantId>, parameterName: String) {
    (this in knownTenants).toMissingCount().requireInRange(0, 0, parameterName)
}

private fun Collection<TenantId>.requireKnownTenants(knownTenants: Set<TenantId>, parameterName: String) {
    count { it !in knownTenants }.requireInRange(0, 0, "$parameterName.unknownCount")
}

private fun Boolean.toMissingCount(): Int = if (this) 0 else 1
