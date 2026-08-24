package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftAssignment
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.AssignmentChange
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageRepository
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** immutable planner proposal을 manager approval 시에만 authoritative assignment로 materialize합니다. */
class ShiftCoverageApprovalService(
    private val plans: ShiftCoveragePlanStore,
    private val assignments: ShiftCoverageRepository,
) {
    private val mutationLock = ReentrantLock()

    fun approve(planId: PlanId, revision: Long): Boolean = mutationLock.withLock {
        val stored = plans.find(planId, revision) ?: return@withLock false
        if (stored.state != ShiftCoveragePlanState.DRAFT || stored.proposal.revision != revision) return@withLock false
        val changes = stored.proposal.assignments.map { planned ->
            val current = assignments.findAssignment(planned.assignmentId)
            val replacement = ShiftAssignment(
                assignmentId = planned.assignmentId,
                siteId = current?.siteId ?: stored.proposal.siteId ?: return@map null,
                shiftId = planned.shiftId,
                workerId = planned.workerId,
                revision = (current?.revision ?: -1L) + 1L,
                pinned = planned.pinned,
                started = current?.started ?: false,
            )
            AssignmentChange(current?.revision, replacement)
        }
        val materializable = changes.filterNotNull()
        if (materializable.size != stored.proposal.assignments.size) return@withLock false
        if (materializable.isNotEmpty() && !assignments.compareAndSetBatch(materializable)) return@withLock false
        plans.approveIfDraft(planId, revision)
    }
}
