package io.bluetape4k.workshop.commerce.voucher.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.time.Instant

internal enum class VoucherPolicyFailure {
    STALE_REVISION,
    INVALID_TRANSITION,
    INVALID_CAMPAIGN_POLICY,
    CAPACITY_BELOW_ALLOCATED,
    NOT_EXPIRED,
    VOUCHER_EXPIRED,
}

internal class VoucherPolicyException(
    val code: VoucherPolicyFailure,
) : RuntimeException(code.name)

/** Pure transition policy shared by HTTP commands, reviews, and reconciliation workers. */
internal object VoucherPolicies : KLogging() {
    fun activateCampaign(
        campaign: CampaignSnapshot,
        expectedRevision: Long,
        now: Instant,
    ): CampaignSnapshot {
        requireRevision(campaign.revision, expectedRevision)
        if (campaign.state != CampaignState.DRAFT && campaign.state != CampaignState.PAUSED) {
            fail(VoucherPolicyFailure.INVALID_TRANSITION)
        }
        validateCampaignPolicy(campaign, now)
        return campaign.copy(state = CampaignState.ACTIVE, revision = campaign.revision + 1)
    }

    fun pauseCampaign(
        campaign: CampaignSnapshot,
        expectedRevision: Long,
    ): CampaignSnapshot {
        requireRevision(campaign.revision, expectedRevision)
        if (campaign.state != CampaignState.ACTIVE) fail(VoucherPolicyFailure.INVALID_TRANSITION)
        return campaign.copy(state = CampaignState.PAUSED, revision = campaign.revision + 1)
    }

    fun endCampaign(
        campaign: CampaignSnapshot,
        expectedRevision: Long,
    ): CampaignSnapshot {
        requireRevision(campaign.revision, expectedRevision)
        if (campaign.state != CampaignState.ACTIVE && campaign.state != CampaignState.PAUSED) {
            fail(VoucherPolicyFailure.INVALID_TRANSITION)
        }
        return campaign.copy(state = CampaignState.ENDED, revision = campaign.revision + 1)
    }

    fun updateCampaignPolicy(
        campaign: CampaignSnapshot,
        expectedRevision: Long,
        capacity: Int,
        perUserLimit: Int,
        redemptionTtlSeconds: Long,
    ): CampaignSnapshot {
        requireRevision(campaign.revision, expectedRevision)
        if (campaign.state == CampaignState.ENDED) fail(VoucherPolicyFailure.INVALID_TRANSITION)
        if (capacity < campaign.allocatedCount) fail(VoucherPolicyFailure.CAPACITY_BELOW_ALLOCATED)
        if (capacity <= 0 || perUserLimit <= 0 || redemptionTtlSeconds <= 0) {
            fail(VoucherPolicyFailure.INVALID_CAMPAIGN_POLICY)
        }
        return campaign.copy(
            capacity = capacity,
            perUserLimit = perUserLimit,
            redemptionTtlSeconds = redemptionTtlSeconds,
            policyVersion = campaign.policyVersion + 1,
            revision = campaign.revision + 1,
        )
    }

    fun approveReview(
        claim: ClaimSnapshot,
        expectedRevision: Long,
    ): TransitionOutcome {
        requireReview(claim, expectedRevision)
        return when (claim.reviewKind) {
            ReviewKind.ALLOCATION -> transition(claim, ClaimState.ALLOCATED, capacityReserved = true, capacityDelta = 1)
            ReviewKind.REDEMPTION -> transition(claim, ClaimState.REDEEMED, capacityReserved = true, capacityDelta = 0)
            null -> fail(VoucherPolicyFailure.INVALID_TRANSITION)
        }
    }

    fun rejectReview(
        claim: ClaimSnapshot,
        expectedRevision: Long,
    ): TransitionOutcome {
        requireReview(claim, expectedRevision)
        return when (claim.reviewKind) {
            ReviewKind.ALLOCATION -> transition(claim, ClaimState.REJECTED, capacityReserved = false, capacityDelta = 0)
            ReviewKind.REDEMPTION -> transition(claim, ClaimState.ALLOCATED, capacityReserved = true, capacityDelta = 0)
            null -> fail(VoucherPolicyFailure.INVALID_TRANSITION)
        }
    }

    fun redeem(
        claim: ClaimSnapshot,
        expectedRevision: Long,
        now: Instant,
    ): TransitionOutcome {
        requireRevision(claim.revision, expectedRevision)
        if (claim.state != ClaimState.ALLOCATED || !claim.capacityReserved) {
            fail(VoucherPolicyFailure.INVALID_TRANSITION)
        }
        if (claim.expiresAt?.let { !now.isBefore(it) } == true) fail(VoucherPolicyFailure.VOUCHER_EXPIRED)
        return transition(claim, ClaimState.REDEEMED, capacityReserved = true, capacityDelta = 0)
    }

    fun release(
        claim: ClaimSnapshot,
        expectedRevision: Long,
    ): TransitionOutcome = terminateReserved(claim, expectedRevision, ClaimState.RELEASED)

    fun expire(
        claim: ClaimSnapshot,
        expectedRevision: Long,
        now: Instant,
    ): TransitionOutcome {
        if (claim.expiresAt?.let(now::isBefore) != false) fail(VoucherPolicyFailure.NOT_EXPIRED)
        return terminateReserved(claim, expectedRevision, ClaimState.EXPIRED)
    }

    fun revoke(
        claim: ClaimSnapshot,
        expectedRevision: Long,
    ): TransitionOutcome = terminateReserved(claim, expectedRevision, ClaimState.REVOKED)

    fun capacityContribution(claim: ClaimSnapshot): Int =
        if (
            claim.capacityReserved &&
            claim.state in setOf(ClaimState.ALLOCATED, ClaimState.REVIEW_REQUIRED, ClaimState.REDEEMED)
        ) {
            1
        } else {
            0
        }

    private fun terminateReserved(
        claim: ClaimSnapshot,
        expectedRevision: Long,
        terminalState: ClaimState,
    ): TransitionOutcome {
        requireRevision(claim.revision, expectedRevision)
        val releasable =
            claim.state == ClaimState.ALLOCATED ||
                (claim.state == ClaimState.REVIEW_REQUIRED && claim.reviewKind == ReviewKind.REDEMPTION)
        if (!releasable || !claim.capacityReserved) fail(VoucherPolicyFailure.INVALID_TRANSITION)
        return transition(claim, terminalState, capacityReserved = false, capacityDelta = -1)
    }

    private fun requireReview(
        claim: ClaimSnapshot,
        expectedRevision: Long,
    ) {
        requireRevision(claim.revision, expectedRevision)
        if (claim.state != ClaimState.REVIEW_REQUIRED) fail(VoucherPolicyFailure.INVALID_TRANSITION)
    }

    private fun transition(
        claim: ClaimSnapshot,
        state: ClaimState,
        capacityReserved: Boolean,
        capacityDelta: Int,
    ): TransitionOutcome =
        TransitionOutcome(
            claim =
                claim.copy(
                    state = state,
                    reviewKind = null,
                    pendingFromState = null,
                    capacityReserved = capacityReserved,
                    revision = claim.revision + 1,
                ),
            capacityDelta = capacityDelta,
        )

    private fun validateCampaignPolicy(
        campaign: CampaignSnapshot,
        now: Instant,
    ) {
        if (
            campaign.capacity <= 0 ||
            campaign.perUserLimit <= 0 ||
            campaign.redemptionTtlSeconds <= 0 ||
            !campaign.startsAt.isBefore(campaign.endsAt) ||
            !now.isBefore(campaign.endsAt)
        ) {
            fail(VoucherPolicyFailure.INVALID_CAMPAIGN_POLICY)
        }
    }

    private fun requireRevision(
        actual: Long,
        expected: Long,
    ) {
        if (actual != expected) fail(VoucherPolicyFailure.STALE_REVISION)
    }

    private fun fail(code: VoucherPolicyFailure): Nothing {
        log.debug { "voucher_policy_rejected code=$code" }
        throw VoucherPolicyException(code)
    }
}
