package io.bluetape4k.workshop.optimization.shiftcoverage.planner

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.CoverageScore
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlannedShift
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlannerFailureCode
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.Shift
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageLimits
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoveragePlanProposal
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoveragePlannerFailure
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageSnapshot
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageReason
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftWorker
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
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
        val currentAssignments = snapshot.assignments.groupBy { it.shiftId }
        val assignments = mutableListOf<PlannedShift>()
        val assignedIntervals = mutableMapOf<WorkerId, MutableList<Shift>>()
        val unassigned = mutableListOf<UnassignedShift>()
        var evaluations = 0
        val fixedShiftIds = mutableSetOf<ShiftId>()

        // 시작되었거나 명시적으로 고정된 assignment는 planner가 이동시키지 않습니다.
        shifts.filter { shift ->
            val stored = currentAssignments[shift.shiftId].orEmpty()
            stored.any { it.started || it.pinned } || shift.isStarted || shift.pinnedWorkerId != null
        }.forEach { shift ->
            fixedShiftIds += shift.shiftId
            val stored = currentAssignments[shift.shiftId].orEmpty().sortedBy { it.assignmentId.value }
            var fixedCount = 0
            if (stored.isNotEmpty()) {
                stored.forEach { fixed ->
                    val planned = PlannedShift(shift.shiftId, fixed.workerId, fixed.assignmentId, pinned = true)
                    assignments += planned
                    assignedIntervals.getOrPut(planned.workerId) { mutableListOf() }.add(shift)
                    fixedCount++
                }
            } else {
                shift.pinnedWorkerId?.let { workerId ->
                    assignments += PlannedShift(shift.shiftId, workerId, deterministicSlotAssignmentId(shift.shiftId, 0), pinned = true)
                    assignedIntervals.getOrPut(workerId) { mutableListOf() }.add(shift)
                    fixedCount++
                }
            }
            if (fixedCount < shift.demand) {
                unassigned += UnassignedShift(shift.shiftId, if (shift.isStarted) ShiftCoverageReason.STARTED_SHIFT else ShiftCoverageReason.PINNED)
            }
        }

        val orderedOpenShifts = shifts.filterNot { it.shiftId in fixedShiftIds }
            .map { shift ->
                val existingCount = currentAssignments[shift.shiftId].orEmpty().size
                ShiftOrder(
                    shift = shift,
                    coverageGap = (shift.demand - existingCount).coerceAtLeast(0),
                    eligibleCandidates = workers.count { worker -> isBaseCandidate(worker, shift, snapshot.siteId) },
                )
            }
            .sortedWith(
                compareByDescending<ShiftOrder> { it.coverageGap }
                    .thenBy { it.eligibleCandidates }
                    .thenBy { it.shift.startAt }
                    .thenBy { it.shift.shiftId.value },
            )

        orderedOpenShifts
            .forEach { shift ->
                budget.check()
                val existingAssignmentIds = currentAssignments[shift.shift.shiftId].orEmpty()
                    .sortedBy { it.assignmentId.value }
                    .map { it.assignmentId }
                var assignedSlots = 0
                var failureReason: ShiftCoverageReason? = null
                for (slotIndex in 0 until shift.shift.demand) {
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
                        if (!worker.skills.containsAll(shift.shift.requiredSkills)) {
                            sawMissingSkill = true
                            return@forEach
                        }
                        if (!covers(worker.availability, shift.shift.startAt, shift.shift.endAt)) {
                            sawUnavailable = true
                            return@forEach
                        }
                        val prior = assignedIntervals[worker.workerId].orEmpty()
                        if (prior.any { overlaps(it, shift.shift) }) {
                            sawOverlap = true
                            return@forEach
                        }
                        if (prior.any { violatesRest(it, shift.shift) }) {
                            sawRest = true
                            return@forEach
                        }
                        val preference = shift.shift.preference?.let { wanted ->
                            worker.preferences.firstOrNull { it.skill == wanted.skill }?.weightMinor ?: 0L
                        } ?: 0L
                        val fairnessDelta = -(2L * prior.size.toLong() + 1L)
                        candidates += Candidate(worker, preference, fairnessDelta, costMinor = 0L)
                    }
                    val selected = candidates.sortedWith(
                        compareByDescending<Candidate> { it.preference }
                            .thenByDescending { it.fairnessDelta }
                            .thenBy { it.costMinor }
                            .thenBy { it.worker.workerId.value },
                    ).firstOrNull()
                    if (selected == null) {
                        failureReason = when {
                            sawOverlap -> ShiftCoverageReason.OVERLAP
                            sawRest -> ShiftCoverageReason.REST_RULE
                            sawMissingSkill && !sawUnavailable -> ShiftCoverageReason.MISSING_SKILL
                            sawUnavailable -> ShiftCoverageReason.UNAVAILABLE
                            else -> ShiftCoverageReason.NO_CANDIDATE
                        }
                        break
                    }
                    assignments += PlannedShift(
                        shiftId = shift.shift.shiftId,
                        workerId = selected.worker.workerId,
                        assignmentId = existingAssignmentIds.getOrNull(slotIndex)
                            ?: deterministicSlotAssignmentId(shift.shift.shiftId, slotIndex),
                        pinned = false,
                    )
                    assignedIntervals.getOrPut(selected.worker.workerId) { mutableListOf() }.add(shift.shift)
                    assignedSlots++
                }
                if (assignedSlots < shift.shift.demand) {
                    unassigned += UnassignedShift(shift.shift.shiftId, failureReason ?: ShiftCoverageReason.NO_CANDIDATE)
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

    private fun deterministicSlotAssignmentId(shiftId: ShiftId, slotIndex: Int): AssignmentId =
        AssignmentId("proposal-${shiftId.value}-slot-${slotIndex + 1}")

    private fun isBaseCandidate(worker: ShiftWorker, shift: Shift, siteId: SiteId): Boolean =
        worker.siteId == siteId &&
            !worker.sickCalled &&
            worker.skills.containsAll(shift.requiredSkills) &&
            covers(worker.availability, shift.startAt, shift.endAt)

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

    private data class ShiftOrder(val shift: Shift, val coverageGap: Int, val eligibleCandidates: Int)

    private data class Candidate(
        val worker: ShiftWorker,
        val preference: Long,
        val fairnessDelta: Long,
        val costMinor: Long,
    )
}
