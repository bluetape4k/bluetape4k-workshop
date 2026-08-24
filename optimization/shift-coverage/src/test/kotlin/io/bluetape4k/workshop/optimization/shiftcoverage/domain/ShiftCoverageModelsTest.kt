package io.bluetape4k.workshop.optimization.shiftcoverage.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test

class ShiftCoverageModelsTest {
    @Test
    fun `ids reject blank and byte oversized values`() {
        assertFailsWith<InvalidShiftCoverageInput> { WorkerId(" ") }
        assertFailsWith<InvalidShiftCoverageInput> {
            IdempotencyKey("a".repeat(ShiftCoverageLimits.MAX_KEY_BYTES + 1))
        }
        IdempotencyKey("a".repeat(ShiftCoverageLimits.MAX_KEY_BYTES)).value.length shouldBeEqualTo
            ShiftCoverageLimits.MAX_KEY_BYTES
    }

    @Test
    fun `uuid and opaque factories use the ecosystem boundary`() {
        val planId = PlanId.new()
        val generationId = GenerationId.new()
        val effect = EffectKey.new()

        planId.value.length shouldBeEqualTo 36
        generationId.value.length shouldBeEqualTo 36
        effect.value.length shouldBeEqualTo ShiftCoverageLimits.OPAQUE_TOKEN_LENGTH
        effect.value.all { it.code in 0x21..0x7e }.shouldBeTrue()
    }

    @Test
    fun `signed score arithmetic rejects overflow`() {
        val first = CoverageScore(costMinor = Long.MAX_VALUE, fairnessMinor = 1)
        assertFailsWith<ShiftCoverageArithmeticError> { first + CoverageScore(costMinor = 1) }
        CoverageScore(costMinor = -5, fairnessMinor = 3).totalMinor shouldBeEqualTo -2L
    }

    @Test
    fun `shift and worker models keep closed invariants`() {
        val start = Instant.parse("2026-08-24T09:00:00Z")
        val end = start.plus(Duration.ofHours(8))
        val worker = ShiftWorker(
            workerId = WorkerId("worker-a"),
            siteId = SiteId("site-a"),
            displayName = "Worker A",
            skills = setOf(Skill("electrical")),
            availability = listOf(TimeInterval(start, end)),
        )
        worker.siteId shouldBeEqualTo SiteId("site-a")
        assertFailsWith<InvalidShiftCoverageInput> {
            Shift(
                shiftId = ShiftId("shift-a"),
                siteId = SiteId("site-a"),
                startAt = end,
                endAt = start,
                requiredSkills = setOf(Skill("electrical")),
            )
        }
    }
}
