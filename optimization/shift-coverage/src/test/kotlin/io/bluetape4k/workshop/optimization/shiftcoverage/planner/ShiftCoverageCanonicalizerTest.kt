package io.bluetape4k.workshop.optimization.shiftcoverage.planner

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.AssignmentId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.Shift
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftAssignment
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageSnapshot
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftWorker
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.SiteId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.Skill
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.TimeInterval
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.WorkerId
import java.time.Instant
import org.junit.jupiter.api.Test

class ShiftCoverageCanonicalizerTest {
    private val start = Instant.parse("2026-08-24T09:00:00Z")
    private val end = Instant.parse("2026-08-24T17:00:00Z")
    private val canonicalizer = ShiftCoverageCanonicalizer()

    @Test
    fun `semantic reorder and unicode normalization produce identical canonical bytes`() {
        val first = snapshot(
            workers = listOf(worker("worker-b", "cafe\u0301"), worker("worker-a", "Worker A")),
            shifts = listOf(shift("shift-b"), shift("shift-a")),
        )
        val second = snapshot(
            workers = listOf(worker("worker-a", "Worker A"), worker("worker-b", "caf\u00e9")),
            shifts = listOf(shift("shift-a"), shift("shift-b")),
        )

        canonicalizer.canonicalBytes(first).decodeToString() shouldBeEqualTo
            canonicalizer.canonicalBytes(second).decodeToString()
        canonicalizer.digest(first) shouldBeEqualTo canonicalizer.digest(second)
    }

    @Test
    fun `canonical output uses schema order and UTC Z`() {
        val bytes = canonicalizer.canonicalBytes(snapshot())
        val json = bytes.decodeToString()
        json.startsWith("{\"aggregateRevision\"").shouldBeTrue()
        json.contains("2026-08-24T09:00:00Z").shouldBeTrue()
        json.contains("2026-08-24T17:00:00Z").shouldBeTrue()
        canonicalizer.digest(snapshot()).value.length shouldBeEqualTo 64
    }

    private fun snapshot(
        workers: List<ShiftWorker> = listOf(worker("worker-a", "Worker A")),
        shifts: List<Shift> = listOf(shift("shift-a")),
    ) = ShiftCoverageSnapshot(
        siteId = SiteId("site-a"),
        workers = workers,
        shifts = shifts,
        assignments = listOf(
            ShiftAssignment(AssignmentId("assignment-a"), SiteId("site-a"), ShiftId("shift-a"), WorkerId("worker-a")),
        ),
        planId = PlanId("plan-a"),
        generationId = GenerationId("generation-a"),
        aggregateRevision = 4,
    )

    private fun worker(id: String, name: String) = ShiftWorker(
        workerId = WorkerId(id),
        siteId = SiteId("site-a"),
        displayName = name,
        skills = setOf(Skill("electrical"), Skill("plumbing")),
        availability = listOf(TimeInterval(start, end)),
    )

    private fun shift(id: String) = Shift(
        shiftId = ShiftId(id),
        siteId = SiteId("site-a"),
        startAt = start,
        endAt = end,
        requiredSkills = setOf(Skill("electrical")),
    )
}
