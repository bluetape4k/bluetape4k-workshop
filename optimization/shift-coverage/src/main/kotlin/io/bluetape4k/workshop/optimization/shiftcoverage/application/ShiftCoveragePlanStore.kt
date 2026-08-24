package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoveragePlanProposal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class ShiftCoveragePlanState { DRAFT, APPROVED, STALE, REJECTED }

data class StoredShiftCoveragePlan(
    val proposal: ShiftCoveragePlanProposal,
    val state: ShiftCoveragePlanState = ShiftCoveragePlanState.DRAFT,
)

/** proposal history를 immutable revision으로 보관하는 application seam입니다. */
class ShiftCoveragePlanStore {
    private val plans = ConcurrentHashMap<Pair<PlanId, Long>, StoredShiftCoveragePlan>()

    fun save(plan: StoredShiftCoveragePlan): StoredShiftCoveragePlan {
        plans[plan.proposal.planId to plan.proposal.revision] = plan
        return plan
    }

    fun find(planId: PlanId, revision: Long): StoredShiftCoveragePlan? = plans[planId to revision]

    fun approveIfDraft(planId: PlanId, revision: Long): Boolean {
        val changed = AtomicBoolean(false)
        plans.computeIfPresent(planId to revision) { _, current ->
            if (current.state == ShiftCoveragePlanState.DRAFT) {
                changed.set(true)
                current.copy(state = ShiftCoveragePlanState.APPROVED)
            } else current
        }
        return changed.get()
    }

    fun markStale(planId: PlanId, revision: Long): Boolean {
        val changed = AtomicBoolean(false)
        plans.computeIfPresent(planId to revision) { _, current ->
            if (current.state == ShiftCoveragePlanState.DRAFT || current.state == ShiftCoveragePlanState.APPROVED) {
                changed.set(true)
                current.copy(state = ShiftCoveragePlanState.STALE)
            } else current
        }
        return changed.get()
    }
}
