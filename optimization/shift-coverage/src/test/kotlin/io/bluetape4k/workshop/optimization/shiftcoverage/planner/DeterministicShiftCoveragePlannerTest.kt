package io.bluetape4k.workshop.optimization.shiftcoverage.planner

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.Shift
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftAssignment
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageLimits
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoveragePlannerFailure
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageReason
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageSnapshot
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftWorker
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.Skill
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.TimeInterval
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerPreference
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test

class DeterministicShiftCoveragePlannerTest {
    private val day = Instant.parse("2026-08-24T09:00:00Z")
    private val end = Instant.parse("2026-08-24T17:00:00Z")
    private val skill = Skill("electrical")
    private val planner = DeterministicShiftCoveragePlanner()

    @Test
    fun `same snapshot produces byte-identical assignments reasons and metrics`() {
        val snapshot = snapshot(
            workers = listOf(worker("worker-b"), worker("worker-a")),
            shifts = listOf(shift("shift-b"), shift("shift-a")),
        )

        val first = planner.plan(snapshot)
        val second = planner.plan(snapshot)

        first shouldBeEqualTo second
    }

    @Test
    fun `demand materializes unique deterministic slot assignments`() {
        val result = planner.plan(
            snapshot(
                workers = listOf(worker("worker-b"), worker("worker-a")),
                shifts = listOf(shift("shift-demand", demand = 2)),
            ),
        )

        result.assignments.map { it.shiftId.value to it.workerId.value to it.assignmentId.value } shouldBeEqualTo listOf(
            "shift-demand" to "worker-a" to "proposal-shift-demand-slot-1",
            "shift-demand" to "worker-b" to "proposal-shift-demand-slot-2",
        )
        result.score.coverageMinor shouldBeEqualTo 2_000L
        result.score.fairnessMinor shouldBeEqualTo -2L
    }

    @Test
    fun `scarce coverage is planned before a flexible shift even when preference differs`() {
        val plumbing = Skill("plumbing")
        val result = planner.plan(
            snapshot(
                workers = listOf(
                    worker("worker-a", skills = setOf(skill)),
                    worker(
                        "worker-b",
                        skills = setOf(skill, plumbing),
                        preferences = listOf(WorkerPreference(skill, 100L)),
                    ),
                ),
                shifts = listOf(
                    shift(
                        "shift-a-flexible",
                        preference = WorkerPreference(skill, 1L),
                    ),
                    shift(
                        "shift-b-scarce",
                        requiredSkills = setOf(skill, plumbing),
                    ),
                ),
            ),
        )

        result.assignments.map { it.shiftId.value to it.workerId.value } shouldBeEqualTo listOf(
            "shift-a-flexible" to "worker-a",
            "shift-b-scarce" to "worker-b",
        )
        result.unassigned shouldBeEqualTo emptyList()
    }

    @Test
    fun `hard rules return stable reasons and preserve started or pinned assignments`() {
        val unavailable = worker("worker-a", sickCalled = true)
        val noSkill = worker("worker-b", skills = setOf(Skill("plumbing")))
        val startedShift = shift("shift-started", startedAt = day.plusSeconds(1))
        val pinned = shift("shift-pinned", pinnedWorkerId = WorkerId("worker-a"))
        val result = planner.plan(
            snapshot(
                workers = listOf(unavailable, noSkill),
                shifts = listOf(shift("shift-no-skill", requiredSkills = setOf(skill)), startedShift, pinned),
                assignments = listOf(
                    ShiftAssignment(AssignmentId("assignment-started"), SiteId("site-a"), startedShift.shiftId, WorkerId("worker-a"), started = true),
                    ShiftAssignment(AssignmentId("assignment-pinned"), SiteId("site-a"), pinned.shiftId, WorkerId("worker-a"), pinned = true),
                ),
            ),
        )

        result.unassigned.map { it.shiftId.value to it.reason } shouldBeEqualTo listOf(
            "shift-no-skill" to ShiftCoverageReason.UNAVAILABLE,
        )
        result.assignments.map { it.shiftId.value } shouldBeEqualTo listOf("shift-pinned", "shift-started")
    }

    @Test
    fun `overlap and minimum rest become no candidate`() {
        val first = shift("shift-a", startAt = day, endAt = day.plus(Duration.ofHours(2)))
        val second = shift("shift-b", startAt = day.plus(Duration.ofHours(2)), endAt = day.plus(Duration.ofHours(3)))
        val result = planner.plan(
            snapshot(
                shifts = listOf(first, second),
                assignments = listOf(ShiftAssignment(AssignmentId("assignment-a"), SiteId("site-a"), first.shiftId, WorkerId("worker-a"))),
            ),
        )

        result.unassigned.single().reason shouldBeEqualTo ShiftCoverageReason.REST_RULE
    }

    @Test
    fun `candidate limit and exact budget are hard failures before materialization`() {
        val tooManyWorkers = (0 until ShiftCoverageLimits.MAX_WORKERS).map { worker("worker-${it.toString().padStart(3, '0')}") }
        val tooManyShifts = (0 until ShiftCoverageLimits.MAX_SHIFTS).map { shift("shift-${it.toString().padStart(3, '0')}", demand = 2) }
        assertFailsWith<ShiftCoveragePlannerFailure> { planner.plan(snapshot(workers = tooManyWorkers, shifts = tooManyShifts)) }

        val clock = FakePlannerClock()
        val budget = StepBudget(clock, Duration.ofSeconds(5))
        clock.now = 5_000_000_000L
        assertFailsWith<ShiftCoveragePlannerFailure> { budget.check() }
    }

    private fun snapshot(
        workers: List<ShiftWorker> = listOf(worker("worker-a")),
        shifts: List<Shift> = listOf(shift("shift-a")),
        assignments: List<ShiftAssignment> = emptyList(),
    ) = ShiftCoverageSnapshot(
        siteId = SiteId("site-a"), workers = workers, shifts = shifts, assignments = assignments,
        planId = PlanId("plan-a"), generationId = GenerationId("generation-a"), aggregateRevision = 1,
    )

    private fun worker(
        id: String,
        skills: Set<Skill> = setOf(skill),
        sickCalled: Boolean = false,
        preferences: List<WorkerPreference> = emptyList(),
    ) = ShiftWorker(
        workerId = WorkerId(id), siteId = SiteId("site-a"), displayName = id, skills = skills,
        availability = listOf(TimeInterval(day, end)), sickCalled = sickCalled, preferences = preferences,
    )

    private fun shift(
        id: String,
        requiredSkills: Set<Skill> = setOf(skill),
        startAt: Instant = day,
        endAt: Instant = day.plus(Duration.ofHours(1)),
        demand: Int = 1,
        preference: WorkerPreference? = null,
        startedAt: Instant? = null,
        pinnedWorkerId: WorkerId? = null,
    ) = Shift(ShiftId(id), SiteId("site-a"), startAt, endAt, requiredSkills, demand = demand, preference = preference, startedAt = startedAt, pinnedWorkerId = pinnedWorkerId)
}

private class FakePlannerClock(var now: Long = 0L) : PlannerClock {
    override fun nanoTime(): Long = now
}
