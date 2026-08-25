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
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository

/** PostgreSQL authority에서 lock order와 expected-revision CAS를 실행하는 좁은 repository입니다. */
@Repository
@Profile("postgres")
class ShiftCoveragePostgresAssignmentRepository : ShiftCoverageAssignmentStore {
    override fun saveAssignment(assignment: ShiftAssignment): ShiftAssignment {
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

    override fun findAssignment(assignmentId: AssignmentId): ShiftAssignment? = ShiftCoverageTransactionSupport.inMutation {
        ShiftCoverageAssignmentsTable.selectAll()
            .where { ShiftCoverageAssignmentsTable.assignmentId eq assignmentId.value }
            .singleOrNull()
            ?.toAssignment()
    }

    /** row를 먼저 `FOR UPDATE`로 잠근 뒤 predicate CAS를 수행해 stale retry를 no-write로 끝냅니다. */
    override fun compareAndSetAssignment(assignmentId: AssignmentId, expectedRevision: Long, replacement: ShiftAssignment): Boolean =
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

    override fun listAssignments(): List<ShiftAssignment> = ShiftCoverageTransactionSupport.inMutation {
        ShiftCoverageAssignmentsTable.selectAll()
            .map { it.toAssignment() }
            .sortedWith(compareBy({ it.siteId.value }, { it.shiftId.value }, { it.workerId.value }, { it.assignmentId.value }))
    }

    override fun findByShift(shiftId: ShiftId): ShiftAssignment? = ShiftCoverageTransactionSupport.inMutation {
        ShiftCoverageAssignmentsTable.selectAll()
            .where { ShiftCoverageAssignmentsTable.shiftId eq shiftId.value }
            .orderBy(ShiftCoverageAssignmentsTable.assignmentId)
            .firstOrNull()
            ?.toAssignment()
    }

    override fun findByWorker(workerId: WorkerId): List<ShiftAssignment> = ShiftCoverageTransactionSupport.inMutation {
        ShiftCoverageAssignmentsTable.selectAll()
            .where { ShiftCoverageAssignmentsTable.workerId eq workerId.value }
            .map { it.toAssignment() }
            .sortedBy { it.assignmentId.value }
    }

    /** 변경 tuple을 정렬한 뒤 모두 잠그고 검증하므로 승인 시 partial write를 허용하지 않습니다. */
    override fun compareAndSetBatch(changes: List<AssignmentChange>): Boolean = ShiftCoverageTransactionSupport.inMutation {
        if (changes.map { it.replacement.assignmentId }.toSet().size != changes.size) return@inMutation false
        val ordered = changes.sortedBy { it.replacement.assignmentId.value }
        val current = ordered.associate { change ->
            val row = ShiftCoverageAssignmentsTable.selectAll()
                .where { ShiftCoverageAssignmentsTable.assignmentId eq change.replacement.assignmentId.value }
                .forUpdate()
                .singleOrNull()
            change.replacement.assignmentId to row?.toAssignment()
        }
        if (ordered.any { change ->
                val present = current[change.replacement.assignmentId]
                when (val expected = change.expectedRevision) {
                    null -> present == null
                    else -> present?.revision == expected
                }.not()
            }) return@inMutation false

        ordered.forEach { change ->
            val replacement = change.replacement
            when (change.expectedRevision) {
                null -> ShiftCoverageAssignmentsTable.insert { statement ->
                    statement[siteId] = replacement.siteId.value
                    statement[shiftId] = replacement.shiftId.value
                    statement[workerId] = replacement.workerId.value
                    statement[assignmentId] = replacement.assignmentId.value
                    statement[revision] = replacement.revision
                    statement[pinned] = replacement.pinned
                    statement[started] = replacement.started
                }
                else -> ShiftCoverageAssignmentsTable.update(
                    where = {
                        (ShiftCoverageAssignmentsTable.assignmentId eq replacement.assignmentId.value) and
                            (ShiftCoverageAssignmentsTable.revision eq change.expectedRevision)
                    },
                ) { statement ->
                    statement[siteId] = replacement.siteId.value
                    statement[shiftId] = replacement.shiftId.value
                    statement[workerId] = replacement.workerId.value
                    statement[revision] = replacement.revision
                    statement[pinned] = replacement.pinned
                    statement[started] = replacement.started
                }
            }
        }
        true
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
