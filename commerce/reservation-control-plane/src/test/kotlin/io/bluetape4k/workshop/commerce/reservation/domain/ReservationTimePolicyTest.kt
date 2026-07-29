package io.bluetape4k.workshop.commerce.reservation.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReservationTimePolicyTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `DST gap local time is rejected deterministically`() {
        val result =
            ReservationTimePolicy.resolve(
                LocalDateTime.parse("2026-03-08T02:30:00"),
                newYork
            )

        assertEquals(LocalTimeRejection.DST_GAP, (result as LocalTimeResolution.Rejected).reason)
    }

    @Test
    fun `DST overlap requires an explicit valid offset`() {
        val local = LocalDateTime.parse("2026-11-01T01:30:00")

        val ambiguous = ReservationTimePolicy.resolve(local, newYork)
        val first = ReservationTimePolicy.resolve(local, newYork, ZoneOffset.ofHours(-4))
        val second = ReservationTimePolicy.resolve(local, newYork, ZoneOffset.ofHours(-5))

        assertEquals(LocalTimeRejection.DST_OVERLAP, (ambiguous as LocalTimeResolution.Rejected).reason)
        assertTrue(first is LocalTimeResolution.Resolved)
        assertTrue(second is LocalTimeResolution.Resolved)
        assertEquals(
            3_600,
            (second as LocalTimeResolution.Resolved).instant.epochSecond -
                (first as LocalTimeResolution.Resolved).instant.epochSecond
        )
    }
}
