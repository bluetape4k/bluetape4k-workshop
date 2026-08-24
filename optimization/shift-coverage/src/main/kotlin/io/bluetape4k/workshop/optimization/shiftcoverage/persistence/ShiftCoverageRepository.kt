package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftAssignment
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 테스트와 demo profile이 공유하는 authoritative assignment CAS seam입니다. */
class ShiftCoverageRepository {
    private val assignments = ConcurrentHashMap<AssignmentId, ShiftAssignment>()
    private val batchLock = ReentrantLock()

    fun saveAssignment(assignment: ShiftAssignment): ShiftAssignment {
        assignments[assignment.assignmentId] = assignment
        return assignment
    }

    fun findAssignment(assignmentId: AssignmentId): ShiftAssignment? = assignments[assignmentId]

    fun listAssignments(): List<ShiftAssignment> = assignments.values.sortedWith(
        compareBy({ it.siteId.value }, { it.shiftId.value }, { it.workerId.value }, { it.assignmentId.value }),
    )

    /** expected revision이 정확히 일치할 때만 replacement를 materialize합니다. */
    fun compareAndSetAssignment(assignmentId: AssignmentId, expectedRevision: Long, replacement: ShiftAssignment): Boolean {
        val changed = AtomicBoolean(false)
        assignments.compute(assignmentId) { _, current ->
            if (current?.revision == expectedRevision) {
                changed.set(true)
                replacement
            } else {
                current
            }
        }
        return changed.get()
    }

    fun findByShift(shiftId: ShiftId): ShiftAssignment? = assignments.values.firstOrNull { it.shiftId == shiftId }

    fun findByWorker(workerId: WorkerId): List<ShiftAssignment> = assignments.values.filter { it.workerId == workerId }

    /** 여러 assignment의 expected revision을 모두 확인한 뒤 한 번에 materialize합니다. */
    fun compareAndSetBatch(changes: List<AssignmentChange>): Boolean = batchLock.withLock {
        if (changes.map { it.replacement.assignmentId }.toSet().size != changes.size) return@withLock false
        val valid = changes.all { change ->
            val current = assignments[change.replacement.assignmentId]
            when (val expected = change.expectedRevision) {
                null -> current == null
                else -> current?.revision == expected
            }
        }
        if (!valid) return@withLock false
        changes.forEach { change -> assignments[change.replacement.assignmentId] = change.replacement }
        true
    }
}

data class AssignmentChange(val expectedRevision: Long?, val replacement: ShiftAssignment)

/** mutation lock order에서 사용하는 canonical key입니다. */
data class ShiftCoverageLockTuple(val key: String) {
    companion object {
        fun event(provider: String, eventId: String) = ShiftCoverageLockTuple("event|$provider|$eventId")
        fun idempotency(namespace: String, key: String) = ShiftCoverageLockTuple("idempotency|$namespace|$key")
        fun worker(siteId: SiteId, workerId: WorkerId) = ShiftCoverageLockTuple("worker|${siteId.value}|${workerId.value}")
        fun shift(siteId: SiteId, startAt: String, shiftId: ShiftId) = ShiftCoverageLockTuple("shift|${siteId.value}|$startAt|${shiftId.value}")
        fun assignment(shiftId: ShiftId, workerId: WorkerId) = ShiftCoverageLockTuple("assignment|${shiftId.value}|${workerId.value}")
        fun plan(planId: String) = ShiftCoverageLockTuple("plan|$planId")
        fun swap(swapId: String) = ShiftCoverageLockTuple("swap|$swapId")
    }
}

/** 입력 순서와 무관하게 UTF-8 lexical ascending tuple을 반환합니다. */
object ShiftCoverageLockOrder {
    fun canonical(tuples: Iterable<ShiftCoverageLockTuple>): List<ShiftCoverageLockTuple> = tuples.sortedWith(Utf8Comparator)

    private object Utf8Comparator : Comparator<ShiftCoverageLockTuple> {
        override fun compare(first: ShiftCoverageLockTuple, second: ShiftCoverageLockTuple): Int {
            val left = first.key.toByteArray(Charsets.UTF_8)
            val right = second.key.toByteArray(Charsets.UTF_8)
            val common = minOf(left.size, right.size)
            for (index in 0 until common) {
                val compared = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
                if (compared != 0) return compared
            }
            return left.size - right.size
        }
    }
}
