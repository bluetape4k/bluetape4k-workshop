package io.bluetape4k.workshop.commerce.reservation.domain

import java.time.Instant
import java.io.Serializable

enum class ResourceState { OPEN, PAUSED, CLOSED }

enum class HoldState { HELD, CONFIRMED, EXPIRED, CANCELLED, RELEASED_BY_OPERATOR }

enum class WaitlistState { WAITING, OFFERED, ACCEPTED, EXPIRED, CANCELLED }

enum class OfferState { ACTIVE, ACCEPTED, EXPIRED, CANCELLED }

enum class TransitionReason {
    OWNER_MISMATCH,
    STALE_REVISION,
    POLICY_VERSION_MISMATCH,
    HOLD_EXPIRED,
    HOLD_CANCELLED,
    HOLD_RELEASED_BY_OPERATOR,
    ALREADY_CONFIRMED,
    INVALID_EXPIRY,
    INVALID_STATE,
}

data class CapacityResourceSnapshot(
    val id: Long,
    val capacity: Int,
    val occupiedCount: Int,
    val revision: Long,
    val policyVersion: Long = 1,
) : Serializable {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(occupiedCount in 0..capacity) { "occupiedCount must be within capacity" }
    }

    val hasCapacity: Boolean get() = occupiedCount < capacity

    companion object {
        private const val serialVersionUID = 1L
    }
}

data class ReservationHoldSnapshot(
    val id: Long,
    val resourceId: Long,
    val ownerDigest: String,
    val state: HoldState,
    val revision: Long,
    val policyVersion: Long,
    val expiresAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

sealed interface TransitionOutcome {
    data class Applied(val hold: ReservationHoldSnapshot) : TransitionOutcome, Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class Rejected(val reason: TransitionReason, val currentRevision: Long) : TransitionOutcome, Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
