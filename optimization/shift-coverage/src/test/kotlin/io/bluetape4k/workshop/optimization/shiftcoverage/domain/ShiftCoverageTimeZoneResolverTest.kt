package io.bluetape4k.workshop.optimization.shiftcoverage.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

class ShiftCoverageTimeZoneResolverTest {
    private val resolver = ShiftCoverageTimeZoneResolver()

    @Test
    fun `spring forward gap is rejected with stable reason`() {
        val failure = assertFailsWith<ShiftCoverageTimeBoundaryException> {
            resolver.resolve(LocalDateTime.of(2026, 3, 29, 2, 30), ZoneId.of("Europe/Paris"))
        }

        failure.code shouldBeEqualTo ShiftCoverageTimeBoundaryCode.TIMEZONE_GAP
    }

    @Test
    fun `fall back ambiguity requires explicit offset`() {
        val local = LocalDateTime.of(2026, 10, 25, 2, 30)
        val failure = assertFailsWith<ShiftCoverageTimeBoundaryException> {
            resolver.resolve(local, ZoneId.of("Europe/Paris"))
        }
        failure.code shouldBeEqualTo ShiftCoverageTimeBoundaryCode.TIMEZONE_AMBIGUOUS

        resolver.resolve(local, ZoneId.of("Europe/Paris"), ZoneOffset.ofHours(2))
            .shouldBeEqualTo(local.toInstant(ZoneOffset.ofHours(2)))
    }
}
