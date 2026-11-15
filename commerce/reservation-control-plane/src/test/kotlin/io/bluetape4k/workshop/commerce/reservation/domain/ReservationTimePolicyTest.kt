package io.bluetape4k.workshop.commerce.reservation.domain

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReservationTimePolicyTest {

    companion object: KLogging()

    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `DST gap local time is rejected deterministically`() {
        val result = ReservationTimePolicy.resolve(
            LocalDateTime.parse("2026-03-08T02:30:00"),
            newYork
        )

        result.shouldBeInstanceOf<LocalTimeResolution.Rejected>()
            .reason shouldBeEqualTo LocalTimeRejection.DST_GAP
    }

    @Test
    fun `DST overlap requires an explicit valid offset`() {
        val local = LocalDateTime.parse("2026-11-01T01:30:00")

        val ambiguous = ReservationTimePolicy.resolve(local, newYork)

        val first = ReservationTimePolicy.resolve(
            localDateTime = local,
            zoneId = newYork,
            preferredOffset = ZoneOffset.ofHours(-4)
        )
        val second = ReservationTimePolicy.resolve(
            localDateTime = local,
            zoneId = newYork,
            preferredOffset = ZoneOffset.ofHours(-5)
        )

        ambiguous.shouldBeInstanceOf<LocalTimeResolution.Rejected>()
            .reason shouldBeEqualTo LocalTimeRejection.DST_OVERLAP

        first.shouldBeInstanceOf<LocalTimeResolution.Resolved>()
        second.shouldBeInstanceOf<LocalTimeResolution.Resolved>()

        (second.instant.epochSecond - first.instant.epochSecond) shouldBeEqualTo 3_600L
    }
}
