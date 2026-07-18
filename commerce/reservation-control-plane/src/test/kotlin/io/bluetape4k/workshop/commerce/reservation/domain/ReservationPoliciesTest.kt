package io.bluetape4k.workshop.commerce.reservation.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ReservationPoliciesTest {
    private val now = Instant.parse("2026-07-19T00:00:00Z")

    @Test
    fun `held reservation can be confirmed by its owner before expiry`() {
        val hold = hold()

        val outcome = ReservationPolicies.confirm(hold, "owner-digest", 1, now)

        assertTrue(outcome is TransitionOutcome.Applied)
        assertEquals(HoldState.CONFIRMED, (outcome as TransitionOutcome.Applied).hold.state)
        assertEquals(2, outcome.hold.revision)
    }

    @Test
    fun `expired reservation is rejected with a stable reason`() {
        val outcome = ReservationPolicies.confirm(hold(expiresAt = now), "owner-digest", 1, now)

        assertEquals(TransitionReason.HOLD_EXPIRED, (outcome as TransitionOutcome.Rejected).reason)
    }

    @Test
    fun `other owner cannot mutate a reservation`() {
        val outcome = ReservationPolicies.cancel(hold(), "other-owner", 1, now)

        assertEquals(TransitionReason.OWNER_MISMATCH, (outcome as TransitionOutcome.Rejected).reason)
    }

    @Test
    fun `stale revision cannot mutate a reservation`() {
        val outcome = ReservationPolicies.confirm(hold(), "owner-digest", 0, now)

        assertEquals(TransitionReason.STALE_REVISION, (outcome as TransitionOutcome.Rejected).reason)
    }

    @Test
    fun `active offer occupies capacity without increment on acceptance`() {
        val resource = CapacityResourceSnapshot(1, capacity = 1, occupiedCount = 1, revision = 7)

        assertFalse(resource.hasCapacity)
        assertEquals(1, ReservationPolicies.occupiedAfterOfferAccepted(resource))
    }

    @Test
    fun `held reservation can be extended by its owner before expiry`() {
        val hold = hold()

        val outcome = ReservationPolicies.extend(hold, "owner-digest", 1, now, now.plusSeconds(90))

        assertTrue(outcome is TransitionOutcome.Applied)
        val extended = (outcome as TransitionOutcome.Applied).hold
        assertEquals(2, extended.revision)
        assertEquals(now.plusSeconds(90), extended.expiresAt)
    }

    @Test
    fun `extension must move expiry forward`() {
        val outcome = ReservationPolicies.extend(hold(), "owner-digest", 1, now, now.plusSeconds(10))

        assertEquals(TransitionReason.INVALID_EXPIRY, (outcome as TransitionOutcome.Rejected).reason)
    }

    private fun hold(expiresAt: Instant = now.plusSeconds(30)) = ReservationHoldSnapshot(
        id = 10,
        resourceId = 1,
        ownerDigest = "owner-digest",
        state = HoldState.HELD,
        revision = 1,
        policyVersion = 1,
        expiresAt = expiresAt,
    )
}
