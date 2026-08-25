package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class ShiftCoverageTableContractTest {
    @Test
    fun `schema exposes authority tables and bounded fingerprint`() {
        ShiftCoverageTables.all.map { it.tableName }.toSet().containsAll(
            setOf(
                "shift_coverage_workers", "shift_coverage_shifts", "shift_coverage_assignments",
                "shift_coverage_inbox", "shift_coverage_idempotency", "shift_coverage_outbox",
            ),
        ).shouldBeTrue()
        ShiftCoverageIdempotencyTable.FINGERPRINT_LENGTH shouldBeEqualTo 64
    }
}
