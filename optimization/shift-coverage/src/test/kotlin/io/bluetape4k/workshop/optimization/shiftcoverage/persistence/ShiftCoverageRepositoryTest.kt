package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftAssignment
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import org.junit.jupiter.api.Test

class ShiftCoverageRepositoryTest {
    @Test
    fun `expected revision CAS changes one row and stale CAS is no-write`() {
        val repository = ShiftCoverageRepository()
        val assignment = ShiftAssignment(AssignmentId("assignment-a"), SiteId("site-a"), ShiftId("shift-a"), WorkerId("worker-a"))
        repository.saveAssignment(assignment)

        repository.compareAndSetAssignment(assignment.assignmentId, 0L, assignment.copy(workerId = WorkerId("worker-b"), revision = 1L)).shouldBeTrue()
        repository.compareAndSetAssignment(assignment.assignmentId, 0L, assignment.copy(workerId = WorkerId("worker-c"), revision = 2L)).shouldBeFalse()
        repository.findAssignment(assignment.assignmentId)?.workerId shouldBeEqualTo WorkerId("worker-b")
        repository.findAssignment(assignment.assignmentId)?.revision shouldBeEqualTo 1L
    }

    @Test
    fun `lock order sorts UTF-8 lexical tuples independent of request order`() {
        val reverse = listOf(
            ShiftCoverageLockTuple.shift(SiteId("site-a"), "2026-08-24T10:00:00Z", ShiftId("shift-b")),
            ShiftCoverageLockTuple.worker(SiteId("site-a"), WorkerId("worker-b")),
            ShiftCoverageLockTuple.shift(SiteId("site-a"), "2026-08-24T09:00:00Z", ShiftId("shift-a")),
            ShiftCoverageLockTuple.worker(SiteId("site-a"), WorkerId("worker-a")),
        )
        ShiftCoverageLockOrder.canonical(reverse) shouldBeEqualTo ShiftCoverageLockOrder.canonical(reverse.reversed())
        ShiftCoverageLockOrder.canonical(reverse).map { it.key } shouldBeEqualTo listOf(
            "shift|site-a|2026-08-24T09:00:00Z|shift-a",
            "shift|site-a|2026-08-24T10:00:00Z|shift-b",
            "worker|site-a|worker-a",
            "worker|site-a|worker-b",
        )
    }
}
