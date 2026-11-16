package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset

internal class ReviewCommandServiceTest: VoucherCommandTestSupport() {

    companion object: KLogging()

    @Test
    fun `allocation review approval reserves capacity and reconstructs the opaque code`() {
        createCampaign()
        val pending = allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-1",
                riskSignal = RiskSignal.REVIEW
            )
        )
        val review = jdbc.foregroundTransaction {
            reviews.findOpen(TENANT_ID, pending.claim.claimId).shouldNotBeNull()
        }

        val approved = reviewCommands.approve(
            ReviewDecisionCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                claimId = pending.claim.claimId,
                reviewId = review.id,
                expectedReviewRevision = 0,
                expectedClaimRevision = 0
            ),
        )

        approved.claim.state shouldBeEqualTo ClaimState.ALLOCATED
        approved.oneTimeCode?.startsWith("V7-").shouldBeTrue()
        campaignSnapshot().allocatedCount shouldBeEqualTo 1
    }

    @Test
    fun `allocation review rejection is terminal without consuming capacity`() {
        createCampaign()
        val pending = allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-1",
                riskSignal = RiskSignal.REVIEW
            )
        )
        val review = jdbc.foregroundTransaction {
            reviews.findOpen(TENANT_ID, pending.claim.claimId).shouldNotBeNull()
        }

        val rejected = reviewCommands.reject(
            ReviewDecisionCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                claimId = pending.claim.claimId,
                reviewId = review.id,
                expectedReviewRevision = 0,
                expectedClaimRevision = 0
            ),
        )

        rejected.claim.state shouldBeEqualTo ClaimState.REJECTED
        rejected.oneTimeCode.shouldBeNull()
        campaignSnapshot().allocatedCount shouldBeEqualTo 0
    }

    @Test
    fun `redemption review rejection returns to allocated without changing capacity`() {
        createCampaign()
        val allocated = allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-1"
            )
        )
        val pending = claimCommands.redeem(
            RedeemVoucherCommand(
                tenantId = TENANT_ID,
                code = allocated.oneTimeCode.shouldNotBeNull(),
                expectedRevision = 0,
                redemptionReference = "order-1",
                riskSignal = RiskSignal.REVIEW,
            ),
        )
        val review = jdbc.foregroundTransaction {
            reviews.findOpen(TENANT_ID, pending.claimId).shouldNotBeNull()
        }

        val rejected = reviewCommands.reject(
            ReviewDecisionCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                claimId = pending.claimId,
                reviewId = review.id,
                expectedReviewRevision = 0,
                expectedClaimRevision = pending.revision
            ),
        )

        rejected.claim.state shouldBeEqualTo ClaimState.ALLOCATED
        campaignSnapshot().allocatedCount shouldBeEqualTo 1
    }

    @Test
    fun `expired redemption review cannot be approved`() {
        createCampaign()
        val allocated = allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-1"
            )
        )
        val pending = claimCommands.redeem(
            RedeemVoucherCommand(
                tenantId = TENANT_ID,
                code = allocated.oneTimeCode.shouldNotBeNull(),
                expectedRevision = 0,
                redemptionReference = "order-1",
                riskSignal = RiskSignal.REVIEW,
            ),
        )
        val review = jdbc.foregroundTransaction {
            reviews.findOpen(TENANT_ID, pending.claimId).shouldNotBeNull()
        }
        configureCommandRuntime(serviceClock = Clock.fixed(NOW + 3_601.seconds(), ZoneOffset.UTC))

        assertFailsWith<VoucherCommandException> {
            reviewCommands.approve(
                ReviewDecisionCommand(
                    tenantId = TENANT_ID,
                    campaignId = CAMPAIGN_ID,
                    claimId = pending.claimId,
                    reviewId = review.id,
                    expectedReviewRevision = 0,
                    expectedClaimRevision = pending.revision
                ),
            )
        }.code shouldBeEqualTo VoucherCommandFailure.VOUCHER_EXPIRED
    }
}
