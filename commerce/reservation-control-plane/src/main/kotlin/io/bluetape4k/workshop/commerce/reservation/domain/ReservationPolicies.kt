package io.bluetape4k.workshop.commerce.reservation.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.time.Instant

/**
 * Evaluates deterministic hold transition policy without performing persistence.
 *
 * A successful decision is advisory until the repository applies the matching PostgreSQL CAS.
 */
object ReservationPolicies : KLogging() {
    fun confirm(
        hold: ReservationHoldSnapshot,
        ownerDigest: String,
        expectedRevision: Long,
        now: Instant,
    ): TransitionOutcome = transition(hold, ownerDigest, expectedRevision, now, HoldState.CONFIRMED)

    fun cancel(
        hold: ReservationHoldSnapshot,
        ownerDigest: String,
        expectedRevision: Long,
        now: Instant,
    ): TransitionOutcome = transition(hold, ownerDigest, expectedRevision, now, HoldState.CANCELLED)

    fun extend(
        hold: ReservationHoldSnapshot,
        ownerDigest: String,
        expectedRevision: Long,
        now: Instant,
        newExpiresAt: Instant,
    ): TransitionOutcome {
        val transition = transition(hold, ownerDigest, expectedRevision, now, HoldState.HELD)
        if (transition is TransitionOutcome.Rejected) {
            return transition
        }
        if (!newExpiresAt.isAfter(hold.expiresAt)) {
            log.debug {
                "reservation_transition_rejected holdId=${hold.id} revision=${hold.revision} reason=INVALID_EXPIRY"
            }
            return TransitionOutcome.Rejected(TransitionReason.INVALID_EXPIRY, hold.revision)
        }
        val extended = hold.copy(revision = hold.revision + 1, expiresAt = newExpiresAt)
        log.debug { "reservation_transition_applied holdId=${hold.id} revision=${extended.revision} state=EXTENDED" }
        return TransitionOutcome.Applied(extended)
    }

    fun occupiedAfterOfferAccepted(resource: CapacityResourceSnapshot): Int = resource.occupiedCount

    private fun transition(
        hold: ReservationHoldSnapshot,
        ownerDigest: String,
        expectedRevision: Long,
        now: Instant,
        target: HoldState,
    ): TransitionOutcome {
        val rejection = when {
            hold.ownerDigest != ownerDigest -> TransitionReason.OWNER_MISMATCH
            hold.revision != expectedRevision -> TransitionReason.STALE_REVISION
            hold.state == HoldState.CONFIRMED -> TransitionReason.ALREADY_CONFIRMED
            hold.state == HoldState.CANCELLED -> TransitionReason.HOLD_CANCELLED
            hold.state == HoldState.RELEASED_BY_OPERATOR -> TransitionReason.HOLD_RELEASED_BY_OPERATOR
            hold.state != HoldState.HELD -> TransitionReason.INVALID_STATE
            !now.isBefore(hold.expiresAt) -> TransitionReason.HOLD_EXPIRED
            else -> null
        }
        if (rejection != null) {
            log.debug { "reservation_transition_rejected holdId=${hold.id} revision=${hold.revision} reason=$rejection" }
            return TransitionOutcome.Rejected(rejection, hold.revision)
        }

        val changed = hold.copy(state = target, revision = hold.revision + 1)
        log.debug { "reservation_transition_applied holdId=${hold.id} revision=${changed.revision} state=$target" }
        return TransitionOutcome.Applied(changed)
    }
}
