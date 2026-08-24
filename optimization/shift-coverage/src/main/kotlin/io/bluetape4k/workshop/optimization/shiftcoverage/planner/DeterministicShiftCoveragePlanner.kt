package io.bluetape4k.workshop.optimization.shiftcoverage.planner

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.CoverageScore
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlannedShift
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlannerFailureCode
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.Shift
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftAssignment
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageLimits
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoveragePlanProposal
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoveragePlannerFailure
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageSnapshot
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageReason
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftWorker
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.TimeInterval
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.UnassignedShift
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import java.time.Duration
import java.time.Instant

/** nanoTime을 주입해 wall-clock을 planner correctness gate로 만들지 않는 경계입니다. */
fun interface PlannerClock { fun nanoTime(): Long }

object SystemPlannerClock : PlannerClock { override fun nanoTime(): Long = System.nanoTime() }

/** planner의 candidate step budget입니다. 정확히 deadline에 도달하면 timeout입니다. */
class StepBudget(
    private val clock: PlannerClock,
    private val budget: Duration = Duration.ofMillis(ShiftCoverageLimits.MAX_PLANNER_MILLIS),
) {
    private val startedAt = clock.nanoTime()
    private val budgetNanos = budget.toNanos()

    init {
        require(!budget.isNegative && !budget.isZero) { "planner budget must be positive" }
    }

    fun check() {
        val elapsed = clock.nanoTime() - startedAt
        if (elapsed >= budgetNanos) {
            throw ShiftCoveragePlannerFailure(PlannerFailureCode.REPLAN_TIMEOUT, "planner step budget exceeded")
        }
    }
}

/** immutable snapshot을 읽고 stable assignment proposal만 반환하는 deterministic planner입니다. */
class DeterministicShiftCoveragePlanner(
    private val canonicalizer: ShiftCoverageCanonicalizer = ShiftCoverageCanonicalizer(),
    private val clock: PlannerClock = SystemPlannerClock,
    private val minimumRest: Duration = Duration.ofMinutes(30),
) {
    fun plan(snapshot: ShiftCoverageSnapshot): ShiftCoveragePlanProposal {
        val budget = StepBudget(clock)
        return plan(snapshot, budget)
    }

    fun plan(snapshot: ShiftCoverageSnapshot, budget: StepBudget): ShiftCoveragePlanProposal {
        budget.check()
        val candidateCount = snapshot.workers.size.toLong() * snapshot.shifts.sumOf { it.demand.toLong() }
        if (candidateCount > ShiftCoverageLimits.MAX_CANDIDATES) {
            throw ShiftCoveragePlannerFailure(PlannerFailureCode.PLANNER_LIMIT_EXCEEDED, "candidate count exceeds planner limit")
        }

        val workers = snapshot.workers.sortedBy { it.workerId.value }
        val shifts = snapshot.shifts.sortedWith(compareBy({ it.startAt }, { it.shiftId.value }))
        val currentAssignments = snapshot.assignments.associateBy { it.shiftId }
        val assignments = mutableListOf<PlannedShift>()
        val assignedIntervals = mutableMapOf<WorkerId, MutableList<Shift>>()
        val unassigned = mutableListOf<UnassignedShift>()
        var evaluations = 0

        // 시작되었거나 명시적으로 고정된 assignment는 planner가 이동시키지 않습니다.
        shifts.filter { shift ->
            val assignment = currentAssignments[shift.shiftId]
            assignment?.started == true || assignment?.pinned == true || shift.isStarted || shift.pinnedWorkerId != null
        }.forEach { shift ->
            val assignment = currentAssignments[shift.shiftId]
            val workerId = assignment?.workerId ?: shift.pinnedWorkerId
            if (workerId != null) {
                val assignmentId = assignment?.assignmentId ?: deterministicAssignmentId(shift.shiftId, workerId)
                assignments += PlannedShift(shift.shiftId, workerId, assignmentId, pinned = true)
                assignedIntervals.getOrPut(workerId) { mutableListOf() }.add(shift)
            } else {
                unassigned += UnassignedShift(shift.shiftId, if (shift.isStarted) ShiftCoverageReason.STARTED_SHIFT else ShiftCoverageReason.PINNED)
            }
        }

        shifts.filterNot { shift -> assignments.any { it.shiftId == shift.shiftId } || unassigned.any { it.shiftId == shift.shiftId } }
            .forEach { shift ->
                budget.check()
                val candidates = mutableListOf<Candidate>()
                var sawUnavailable = false
                var sawMissingSkill = false
                var sawOverlap = false
                var sawRest = false
                workers.forEach { worker ->
                    budget.check()
                    evaluations++
                    if (worker.siteId != snapshot.siteId) return@forEach
                    if (worker.sickCalled) {
                        sawUnavailable = true
                        return@forEach
                    }
                    if (!worker.skills.containsAll(shift.requiredSkills)) {
                        sawMissingSkill = true
                        return@forEach
                    }
                    if (!covers(worker.availability, shift.startAt, shift.endAt)) {
                        sawUnavailable = true
                        return@forEach
                    }
                    val prior = assignedIntervals[worker.workerId].orEmpty()
                    if (prior.any { overlaps(it, shift) }) {
                        sawOverlap = true
                        return@forEach
                    }
                    if (prior.any { violatesRest(it, shift) }) {
                        sawRest = true
                        return@forEach
                    }
                    val preference = shift.preference?.let { wanted ->
                        worker.preferences.firstOrNull { it.skill == wanted.skill }?.weightMinor ?: 0L
                    } ?: 0L
                    candidates += Candidate(worker, preference, prior.size)
                }
                val selected = candidates.sortedWith(
                    compareByDescending<Candidate> { it.preference }
                        .thenBy { it.assignedCount }
                        .thenBy { it.worker.workerId.value },
                ).firstOrNull()
                if (selected == null) {
                    val reason = when {
                        sawOverlap -> ShiftCoverageReason.OVERLAP
                        sawRest -> ShiftCoverageReason.REST_RULE
                        sawMissingSkill && !sawUnavailable -> ShiftCoverageReason.MISSING_SKILL
                        sawUnavailable -> ShiftCoverageReason.UNAVAILABLE
                        else -> ShiftCoverageReason.NO_CANDIDATE
                    }
                    unassigned += UnassignedShift(shift.shiftId, reason)
                } else {
                    val assignment = PlannedShift(
                        shiftId = shift.shiftId,
                        workerId = selected.worker.workerId,
                        assignmentId = currentAssignments[shift.shiftId]?.assignmentId
                            ?: deterministicAssignmentId(shift.shiftId, selected.worker.workerId),
                        pinned = false,
                    )
                    assignments += assignment
                    assignedIntervals.getOrPut(selected.worker.workerId) { mutableListOf() }.add(shift)
                }
            }

        val orderedAssignments = assignments.sortedWith(compareBy({ it.shiftId.value }, { it.workerId.value }, { it.assignmentId.value }))
        val orderedUnassigned = unassigned.sortedBy { it.shiftId.value }
        val assignedByWorker = orderedAssignments.groupingBy { it.workerId }.eachCount()
        val fairness = assignedByWorker.values.sumOf { count -> -count.toLong() * count.toLong() }
        return ShiftCoveragePlanProposal(
            planId = snapshot.planId,
            generationId = snapshot.generationId,
            revision = snapshot.aggregateRevision,
            siteId = snapshot.siteId,
            assignments = orderedAssignments,
            unassigned = orderedUnassigned,
            score = CoverageScore(coverageMinor = orderedAssignments.size.toLong() * 1_000L, fairnessMinor = fairness),
            candidateEvaluations = evaluations,
            snapshotDigest = canonicalizer.digest(snapshot),
        )
    }

    private fun deterministicAssignmentId(shiftId: io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId, workerId: WorkerId): AssignmentId =
        AssignmentId("proposal-${shiftId.value}-${workerId.value}")

    private fun covers(windows: List<TimeInterval>, start: Instant, end: Instant): Boolean =
        windows.any { !start.isBefore(it.startAt) && !end.isAfter(it.endAt) }

    private fun overlaps(first: Shift, second: Shift): Boolean = first.startAt < second.endAt && second.startAt < first.endAt

    private fun violatesRest(first: Shift, second: Shift): Boolean {
        val gap = when {
            first.endAt <= second.startAt -> Duration.between(first.endAt, second.startAt)
            second.endAt <= first.startAt -> Duration.between(second.endAt, first.startAt)
            else -> Duration.ZERO
        }
        return gap < minimumRest
    }

    private data class Candidate(val worker: ShiftWorker, val preference: Long, val assignedCount: Int)
}
