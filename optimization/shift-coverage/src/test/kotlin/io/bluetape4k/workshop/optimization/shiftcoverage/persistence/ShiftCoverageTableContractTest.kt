package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContainAll
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class ShiftCoverageTableContractTest {

    companion object: KLogging()

    @Test
    fun `schema exposes authority tables and bounded fingerprint`() {
        ShiftCoverageTables.all
            .map { it.tableName }
            .toSet()
            .shouldContainAll(
                setOf(
                    "shift_coverage_workers", "shift_coverage_shifts", "shift_coverage_assignments",
                    "shift_coverage_inbox", "shift_coverage_idempotency", "shift_coverage_outbox",
                ),
            )
        
        ShiftCoverageIdempotencyTable.FINGERPRINT_LENGTH shouldBeEqualTo 64
    }
}
