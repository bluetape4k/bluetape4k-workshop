package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftAssignment
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** PostgreSQL authority에서 lock order와 expected-revision CAS를 실행하는 좁은 repository입니다. */
class ShiftCoveragePostgresAssignmentRepository {
    fun saveAssignment(assignment: ShiftAssignment): ShiftAssignment {
        ShiftCoverageTransactionSupport.inMutation {
            ShiftCoverageAssignmentsTable.insert { statement ->
                statement[siteId] = assignment.siteId.value
                statement[shiftId] = assignment.shiftId.value
                statement[workerId] = assignment.workerId.value
                statement[assignmentId] = assignment.assignmentId.value
                statement[revision] = assignment.revision
                statement[pinned] = assignment.pinned
                statement[started] = assignment.started
            }
        }
        return assignment
    }

    fun findAssignment(assignmentId: AssignmentId): ShiftAssignment? = ShiftCoverageTransactionSupport.inMutation {
        ShiftCoverageAssignmentsTable.selectAll()
            .where { ShiftCoverageAssignmentsTable.assignmentId eq assignmentId.value }
            .singleOrNull()
            ?.toAssignment()
    }

    /** row를 먼저 `FOR UPDATE`로 잠근 뒤 predicate CAS를 수행해 stale retry를 no-write로 끝냅니다. */
    fun compareAndSetAssignment(assignmentId: AssignmentId, expectedRevision: Long, replacement: ShiftAssignment): Boolean =
        ShiftCoverageTransactionSupport.inMutation {
            val current = ShiftCoverageAssignmentsTable.selectAll()
                .where { ShiftCoverageAssignmentsTable.assignmentId eq assignmentId.value }
                .forUpdate()
                .singleOrNull()
                ?: return@inMutation false
            if (current[ShiftCoverageAssignmentsTable.revision] != expectedRevision) return@inMutation false
            ShiftCoverageAssignmentsTable.update(
                where = {
                    (ShiftCoverageAssignmentsTable.assignmentId eq assignmentId.value) and
                        (ShiftCoverageAssignmentsTable.revision eq expectedRevision)
                },
            ) { statement ->
                statement[siteId] = replacement.siteId.value
                statement[shiftId] = replacement.shiftId.value
                statement[workerId] = replacement.workerId.value
                statement[revision] = replacement.revision
                statement[pinned] = replacement.pinned
                statement[started] = replacement.started
            } == 1
        }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toAssignment(): ShiftAssignment = ShiftAssignment(
        assignmentId = AssignmentId(this[ShiftCoverageAssignmentsTable.assignmentId]),
        siteId = SiteId(this[ShiftCoverageAssignmentsTable.siteId]),
        shiftId = ShiftId(this[ShiftCoverageAssignmentsTable.shiftId]),
        workerId = WorkerId(this[ShiftCoverageAssignmentsTable.workerId]),
        revision = this[ShiftCoverageAssignmentsTable.revision],
        pinned = this[ShiftCoverageAssignmentsTable.pinned],
        started = this[ShiftCoverageAssignmentsTable.started],
    )
}
