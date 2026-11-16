package io.bluetape4k.workshop.commerce.reservation.domain

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Instant

class ReservationPoliciesTest {

    companion object: KLogging()

    private val now = Instant.parse("2026-07-19T00:00:00Z")

    @Test
    fun `held reservation can be confirmed by its owner before expiry`() {
        val hold = hold()

        val outcome = ReservationPolicies.confirm(
            hold = hold,
            ownerDigest = "owner-digest",
            expectedRevision = 1,
            now = now
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Applied>()
        outcome.hold.state shouldBeEqualTo HoldState.CONFIRMED
        outcome.hold.revision shouldBeEqualTo 2
    }

    @Test
    fun `expired reservation is rejected with a stable reason`() {
        val outcome = ReservationPolicies.confirm(
            hold = hold(expiresAt = now),
            ownerDigest = "owner-digest",
            expectedRevision = 1,
            now = now
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Rejected>()
            .reason shouldBeEqualTo TransitionReason.HOLD_EXPIRED
    }

    @Test
    fun `other owner cannot mutate a reservation`() {
        val outcome = ReservationPolicies.cancel(
            hold = hold(),
            ownerDigest = "other-owner",
            expectedRevision = 1,
            now = now
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Rejected>()
            .reason shouldBeEqualTo TransitionReason.OWNER_MISMATCH
    }

    @Test
    fun `stale revision cannot mutate a reservation`() {
        val outcome = ReservationPolicies.confirm(
            hold = hold(),
            ownerDigest = "owner-digest",
            expectedRevision = 0,
            now = now
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Rejected>()
            .reason shouldBeEqualTo TransitionReason.STALE_REVISION
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

        val outcome = ReservationPolicies.extend(
            hold = hold,
            ownerDigest = "owner-digest",
            expectedRevision = 1,
            now = now,
            newExpiresAt = now.plusSeconds(90)
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Applied>()

        val extended = outcome.hold
        extended.revision shouldBeEqualTo 2
        extended.expiresAt shouldBeEqualTo now.plusSeconds(90)
    }

    @Test
    fun `extension must move expiry forward`() {
        val outcome = ReservationPolicies.extend(
            hold = hold(),
            ownerDigest = "owner-digest",
            expectedRevision = 1,
            now = now,
            newExpiresAt = now.plusSeconds(10)
        )

        outcome.shouldBeInstanceOf<TransitionOutcome.Rejected>()
            .reason shouldBeEqualTo TransitionReason.INVALID_EXPIRY
    }

    private fun hold(expiresAt: Instant = now + 30.seconds()): ReservationHoldSnapshot =
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
