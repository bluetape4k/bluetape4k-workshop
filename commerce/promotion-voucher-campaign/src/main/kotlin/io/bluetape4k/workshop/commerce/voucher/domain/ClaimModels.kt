package io.bluetape4k.workshop.commerce.voucher.domain

import java.time.Instant
import java.util.UUID

internal enum class ClaimState {
    ELIGIBLE,
    REVIEW_REQUIRED,
    ALLOCATED,
    REDEEMED,
    RELEASED,
    EXPIRED,
    REVOKED,
    REJECTED,
}

internal enum class ReviewKind {
    ALLOCATION,
    REDEMPTION,
}

internal data class ClaimSnapshot(
    val tenantId: String,
    val campaignId: UUID,
    val claimId: UUID,
    val state: ClaimState,
    val reviewKind: ReviewKind?,
    val pendingFromState: ClaimState?,
    val capacityReserved: Boolean,
    val allocationPolicyVersion: Long,
    val expiresAt: Instant?,
    val revision: Long,
) {
    init {
        if (state == ClaimState.REVIEW_REQUIRED) {
            require(reviewKind != null && pendingFromState != null) {
                "review-required claim must preserve review context"
            }
        } else {
            require(reviewKind == null && pendingFromState == null) {
                "non-review claim cannot preserve review context"
            }
        }
    }
}

internal data class TransitionOutcome(
    val claim: ClaimSnapshot,
    val capacityDelta: Int,
)
