package io.bluetape4k.workshop.commerce.reservation.domain

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ReservationPoliciesTest {
    private val now = Instant.parse("2026-07-19T00:00:00Z")

    @Test
    fun `held reservation can be confirmed by its owner before expiry`() {
        val hold = hold()

        val outcome = ReservationPolicies.confirm(hold, "owner-digest", 1, now)

        (outcome is TransitionOutcome.Applied).shouldBeTrue()
        (outcome as TransitionOutcome.Applied).hold.state shouldBeEqualTo HoldState.CONFIRMED
        outcome.hold.revision shouldBeEqualTo 2
    }

    @Test
    fun `expired reservation is rejected with a stable reason`() {
        val outcome = ReservationPolicies.confirm(hold(expiresAt = now), "owner-digest", 1, now)

        (outcome as TransitionOutcome.Rejected).reason shouldBeEqualTo TransitionReason.HOLD_EXPIRED
    }

    @Test
    fun `other owner cannot mutate a reservation`() {
        val outcome = ReservationPolicies.cancel(hold(), "other-owner", 1, now)

        (outcome as TransitionOutcome.Rejected).reason shouldBeEqualTo TransitionReason.OWNER_MISMATCH
    }

    @Test
    fun `stale revision cannot mutate a reservation`() {
        val outcome = ReservationPolicies.confirm(hold(), "owner-digest", 0, now)

        (outcome as TransitionOutcome.Rejected).reason shouldBeEqualTo TransitionReason.STALE_REVISION
    }

    @Test
    fun `active offer occupies capacity without increment on acceptance`() {
        val resource = CapacityResourceSnapshot(1, capacity = 1, occupiedCount = 1, revision = 7)

        resource.hasCapacity.shouldBeFalse()
        ReservationPolicies.occupiedAfterOfferAccepted(resource) shouldBeEqualTo 1
    }

    @Test
    fun `held reservation can be extended by its owner before expiry`() {
        val hold = hold()

        val outcome = ReservationPolicies.extend(hold, "owner-digest", 1, now, now.plusSeconds(90))

        (outcome is TransitionOutcome.Applied).shouldBeTrue()
        val extended = (outcome as TransitionOutcome.Applied).hold
        extended.revision shouldBeEqualTo 2
        extended.expiresAt shouldBeEqualTo now.plusSeconds(90)
    }

    @Test
    fun `extension must move expiry forward`() {
        val outcome = ReservationPolicies.extend(hold(), "owner-digest", 1, now, now.plusSeconds(10))

        (outcome as TransitionOutcome.Rejected).reason shouldBeEqualTo TransitionReason.INVALID_EXPIRY
    }

    private fun hold(expiresAt: Instant = now.plusSeconds(30)) =
        ReservationHoldSnapshot(
            id = 10,
            resourceId = 1,
            ownerDigest = "owner-digest",
            state = HoldState.HELD,
            revision = 1,
            policyVersion = 1,
            expiresAt = expiresAt
        )
}
