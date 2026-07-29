package io.bluetape4k.workshop.commerce.reservation.domain

import java.io.Serializable
import java.time.Instant

/** current occupancy와 독립적으로 제어되는 availability lifecycle입니다. */
enum class ResourceState { OPEN, PAUSED, CLOSED }

/** capacity를 소유하는 reservation hold의 durable lifecycle입니다. */
enum class HoldState { HELD, CONFIRMED, EXPIRED, CANCELLED, RELEASED_BY_OPERATOR }

/** durable FIFO entry lifecycle입니다. `OFFERED`는 capacity slot이 이 entry에 계속 예약되어 있음을 뜻합니다. */
enum class WaitlistState { WAITING, OFFERED, ACCEPTED, EXPIRED, CANCELLED }

/** `OFFERED` waitlist entry와 짝을 이루는 short-lived offer lifecycle입니다. */
enum class OfferState { ACTIVE, ACCEPTED, EXPIRED, CANCELLED }

/** caller에게 반환하고 audit event에 기록하는 안정적인 rejection vocabulary입니다. */
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

/** database transaction 바깥에서 capacity policy를 평가할 때 사용하는 immutable input입니다. */
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

/** [ReservationPolicies]가 소비하는 immutable hold state입니다. */
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

/** 해당 repository CAS와 함께 commit되어야 하는 policy result입니다. */
sealed interface TransitionOutcome {
    data class Applied(
        val hold: ReservationHoldSnapshot,
    ) : TransitionOutcome,
        Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class Rejected(
        val reason: TransitionReason,
        val currentRevision: Long,
    ) : TransitionOutcome,
        Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
