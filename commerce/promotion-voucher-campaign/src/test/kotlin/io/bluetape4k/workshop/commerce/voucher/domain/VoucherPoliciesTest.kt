package io.bluetape4k.workshop.commerce.voucher.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class VoucherPoliciesTest {
    @Test
    fun `campaign activation requires a valid period and capacity`() {
        val draft = campaign(state = CampaignState.DRAFT)

        val active = VoucherPolicies.activateCampaign(draft, draft.revision, NOW)

        active.state shouldBeEqualTo CampaignState.ACTIVE
        active.revision shouldBeEqualTo draft.revision + 1
        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.activateCampaign(
                draft.copy(capacity = 0),
                draft.revision,
                NOW,
            )
        }.code shouldBeEqualTo VoucherPolicyFailure.INVALID_CAMPAIGN_POLICY
    }

    @Test
    fun `ended campaign cannot resume and capacity cannot fall below allocations`() {
        val ended = campaign(state = CampaignState.ENDED)

        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.activateCampaign(ended, ended.revision, NOW)
        }.code shouldBeEqualTo VoucherPolicyFailure.INVALID_TRANSITION
        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.updateCampaignPolicy(
                campaign(state = CampaignState.ACTIVE, allocatedCount = 4),
                expectedRevision = 7,
                capacity = 3,
                perUserLimit = 1,
                redemptionTtlSeconds = 3600,
            )
        }.code shouldBeEqualTo VoucherPolicyFailure.CAPACITY_BELOW_ALLOCATED
    }

    @Test
    fun `redemption review rejection returns to allocated without changing capacity`() {
        val claim = claim(state = ClaimState.REVIEW_REQUIRED, reviewKind = ReviewKind.REDEMPTION, capacityReserved = true)

        val outcome = VoucherPolicies.rejectReview(claim, expectedRevision = claim.revision)

        outcome.claim.state shouldBeEqualTo ClaimState.ALLOCATED
        outcome.claim.reviewKind shouldBeEqualTo null
        outcome.claim.capacityReserved.shouldBeTrue()
        outcome.capacityDelta shouldBeEqualTo 0
    }

    @Test
    fun `allocation review approval contributes capacity once`() {
        val claim = claim(state = ClaimState.REVIEW_REQUIRED, reviewKind = ReviewKind.ALLOCATION, capacityReserved = false)

        val outcome = VoucherPolicies.approveReview(claim, expectedRevision = claim.revision)

        outcome.claim.state shouldBeEqualTo ClaimState.ALLOCATED
        outcome.claim.capacityReserved.shouldBeTrue()
        outcome.capacityDelta shouldBeEqualTo 1
        VoucherPolicies.capacityContribution(outcome.claim) shouldBeEqualTo 1
    }

    @Test
    fun `allocation rejection is terminal without consuming capacity`() {
        val claim = claim(state = ClaimState.REVIEW_REQUIRED, reviewKind = ReviewKind.ALLOCATION, capacityReserved = false)

        val outcome = VoucherPolicies.rejectReview(claim, expectedRevision = claim.revision)

        outcome.claim.state shouldBeEqualTo ClaimState.REJECTED
        outcome.claim.capacityReserved.shouldBeFalse()
        outcome.capacityDelta shouldBeEqualTo 0
    }

    @Test
    fun `release expiry and revoke decrement reserved capacity exactly once`() {
        val allocated = claim(state = ClaimState.ALLOCATED, capacityReserved = true)

        VoucherPolicies.release(allocated, allocated.revision).capacityDelta shouldBeEqualTo -1
        VoucherPolicies.expire(allocated.copy(expiresAt = NOW), allocated.revision, NOW).capacityDelta shouldBeEqualTo -1
        VoucherPolicies.revoke(allocated, allocated.revision).capacityDelta shouldBeEqualTo -1

        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.release(
                VoucherPolicies.release(allocated, allocated.revision).claim,
                allocated.revision + 1,
            )
        }.code shouldBeEqualTo VoucherPolicyFailure.INVALID_TRANSITION
    }

    @Test
    fun `redeemed claims consume capacity and cannot be revoked`() {
        val allocated = claim(state = ClaimState.ALLOCATED, capacityReserved = true)
        val redeemed = VoucherPolicies.redeem(allocated, allocated.revision, NOW).claim

        redeemed.state shouldBeEqualTo ClaimState.REDEEMED
        VoucherPolicies.capacityContribution(redeemed) shouldBeEqualTo 1
        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.revoke(redeemed, redeemed.revision)
        }.code shouldBeEqualTo VoucherPolicyFailure.INVALID_TRANSITION
    }

    @Test
    fun `stale revision fails before transition`() {
        val claim = claim(state = ClaimState.REVIEW_REQUIRED, reviewKind = ReviewKind.REDEMPTION, capacityReserved = true)

        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.approveReview(claim, expectedRevision = claim.revision - 1)
        }.code shouldBeEqualTo VoucherPolicyFailure.STALE_REVISION
    }

    @Test
    fun `expiry requires the claim deadline to have elapsed`() {
        val claim = claim(state = ClaimState.ALLOCATED, capacityReserved = true, expiresAt = NOW.plusSeconds(1))

        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.expire(claim, claim.revision, NOW)
        }.code shouldBeEqualTo VoucherPolicyFailure.NOT_EXPIRED
    }

    private fun claim(
        state: ClaimState,
        reviewKind: ReviewKind? = null,
        capacityReserved: Boolean = false,
        expiresAt: Instant = NOW.plusSeconds(3600),
    ): ClaimSnapshot =
        ClaimSnapshot(
            tenantId = "tenant-a",
            campaignId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab"),
            claimId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890bc"),
            state = state,
            reviewKind = reviewKind,
            pendingFromState =
                when (reviewKind) {
                    ReviewKind.ALLOCATION -> ClaimState.ELIGIBLE
                    ReviewKind.REDEMPTION -> ClaimState.ALLOCATED
                    null -> null
                },
            capacityReserved = capacityReserved,
            allocationPolicyVersion = 3,
            expiresAt = expiresAt,
            revision = 11,
        )

    private fun campaign(
        state: CampaignState,
        allocatedCount: Int = 0,
    ): CampaignSnapshot =
        CampaignSnapshot(
            tenantId = "tenant-a",
            campaignId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab"),
            state = state,
            startsAt = NOW.minusSeconds(60),
            endsAt = NOW.plusSeconds(3600),
            capacity = 10,
            allocatedCount = allocatedCount,
            perUserLimit = 1,
            redemptionTtlSeconds = 3600,
            policyVersion = 3,
            revision = 7,
        )

    companion object {
        private val NOW: Instant = Instant.parse("2026-07-19T00:00:00Z")
    }
}
