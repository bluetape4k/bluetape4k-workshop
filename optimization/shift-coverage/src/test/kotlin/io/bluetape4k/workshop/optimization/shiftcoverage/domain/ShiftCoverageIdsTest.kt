package io.bluetape4k.workshop.optimization.shiftcoverage.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class ShiftCoverageIdsTest {
    @Test
    fun `event and digest have stable wire constraints`() {
        EventId("event-1").value shouldBeEqualTo "event-1"
        assertFailsWith<InvalidShiftCoverageInput> { SnapshotDigest("abc") }
        assertFailsWith<InvalidShiftCoverageInput> { EventId("x".repeat(201)) }
    }
}
