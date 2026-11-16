package io.bluetape4k.workshop.optimization.shiftcoverage.adapter

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.InvalidShiftCoverageInput
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageEventType
import org.junit.jupiter.api.Test

class ShiftCoverageCallbackCanonicalizerTest {

    companion object: KLogging()

    private val canonicalizer = ShiftCoverageCallbackCanonicalizer()

    @Test
    fun `closed event envelope canonicalizes whitespace and escapes`() {
        val body = "{ \"event\" : \"availability.changed\" }".toUtf8Bytes()

        canonicalizer.parse(body).eventType shouldBeEqualTo ShiftCoverageEventType.AVAILABILITY_CHANGED
        canonicalizer.canonicalBytes(body).toUtf8String() shouldBeEqualTo "{\"event\":\"availability.changed\"}"
    }

    @Test
    fun `unknown and duplicate fields are rejected before state write`() {
        assertFailsWith<InvalidShiftCoverageInput> {
            canonicalizer.parse("{\"event\":\"availability.changed\",\"secret\":\"x\"}".toUtf8Bytes())
        }
        assertFailsWith<InvalidShiftCoverageInput> {
            canonicalizer.parse("{\"event\":\"availability.changed\",\"event\":\"shift.started\"}".toUtf8Bytes())
        }
    }

    @Test
    fun `trailing JSON tokens are rejected before state write`() {
        assertFailsWith<InvalidShiftCoverageInput> {
            canonicalizer.canonicalBytes("{\"event\":\"availability.changed\"} {}".toUtf8Bytes())
        }
    }
}
