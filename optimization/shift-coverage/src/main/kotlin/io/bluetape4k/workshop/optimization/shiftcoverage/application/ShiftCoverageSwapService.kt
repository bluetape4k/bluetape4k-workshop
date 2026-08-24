package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.IdempotencyKey
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageConflict
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageConflictCode
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageRepository

/** worker가 제안하고 manager가 확인하는 shift swap acceptance command입니다. */
data class ShiftSwapAcceptance(
    val assignmentId: AssignmentId,
    val targetWorkerId: WorkerId,
    val expectedRevision: Long,
    val expectedPlanRevision: Long,
    val idempotencyKey: IdempotencyKey,
)

class ShiftCoverageSwapService(private val repository: ShiftCoverageRepository) {
    fun accept(command: ShiftSwapAcceptance): Boolean {
        if (command.expectedRevision < 0L || command.expectedPlanRevision < 0L) return false
        val current = repository.findAssignment(command.assignmentId) ?: return false
        if (current.started || current.pinned || current.revision != command.expectedRevision) return false
        val replacement = current.copy(workerId = command.targetWorkerId, revision = current.revision + 1L, pinned = false)
        return repository.compareAndSetAssignment(command.assignmentId, command.expectedRevision, replacement)
    }

    fun requireAccepted(command: ShiftSwapAcceptance) {
        if (!accept(command)) throw ShiftCoverageConflict(ShiftCoverageConflictCode.REVISION_CONFLICT, "swap revision conflict")
    }
}
