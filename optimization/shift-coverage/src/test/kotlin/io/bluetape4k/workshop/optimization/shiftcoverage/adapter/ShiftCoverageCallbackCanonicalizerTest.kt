package io.bluetape4k.workshop.optimization.shiftcoverage.adapter

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.InvalidShiftCoverageInput
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageEventType
import org.junit.jupiter.api.Test

class ShiftCoverageCallbackCanonicalizerTest {
    private val canonicalizer = ShiftCoverageCallbackCanonicalizer()

    @Test
    fun `closed event envelope canonicalizes whitespace and escapes`() {
        val body = "{ \"event\" : \"availability.changed\" }".toByteArray()

        canonicalizer.parse(body).eventType shouldBeEqualTo ShiftCoverageEventType.AVAILABILITY_CHANGED
        canonicalizer.canonicalBytes(body).decodeToString() shouldBeEqualTo "{\"event\":\"availability.changed\"}"
    }

    @Test
    fun `unknown and duplicate fields are rejected before state write`() {
        assertFailsWith<InvalidShiftCoverageInput> {
            canonicalizer.parse("{\"event\":\"availability.changed\",\"secret\":\"x\"}".toByteArray())
        }
        assertFailsWith<InvalidShiftCoverageInput> {
            canonicalizer.parse("{\"event\":\"availability.changed\",\"event\":\"shift.started\"}".toByteArray())
        }
    }
}
