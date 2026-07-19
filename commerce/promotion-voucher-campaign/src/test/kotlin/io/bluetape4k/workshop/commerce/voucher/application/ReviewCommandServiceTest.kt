package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset

internal class ReviewCommandServiceTest : VoucherCommandTestSupport() {
    @Test
    fun `allocation review approval reserves capacity and reconstructs the opaque code`() {
        createCampaign()
        val pending = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1", RiskSignal.REVIEW))
        val review = jdbc.foregroundTransaction { checkNotNull(reviews.findOpen(TENANT_ID, pending.claim.claimId)) }

        val approved =
            reviewCommands.approve(
                ReviewDecisionCommand(TENANT_ID, CAMPAIGN_ID, pending.claim.claimId, review.id, 0, 0),
            )

        approved.claim.state shouldBeEqualTo ClaimState.ALLOCATED
        approved.oneTimeCode?.startsWith("V7-") shouldBeEqualTo true
        campaignSnapshot().allocatedCount shouldBeEqualTo 1
    }

    @Test
    fun `allocation review rejection is terminal without consuming capacity`() {
        createCampaign()
        val pending = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1", RiskSignal.REVIEW))
        val review = jdbc.foregroundTransaction { checkNotNull(reviews.findOpen(TENANT_ID, pending.claim.claimId)) }

        val rejected =
            reviewCommands.reject(
                ReviewDecisionCommand(TENANT_ID, CAMPAIGN_ID, pending.claim.claimId, review.id, 0, 0),
            )

        rejected.claim.state shouldBeEqualTo ClaimState.REJECTED
        rejected.oneTimeCode.shouldBeNull()
        campaignSnapshot().allocatedCount shouldBeEqualTo 0
    }

    @Test
    fun `redemption review rejection returns to allocated without changing capacity`() {
        createCampaign()
        val allocated = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))
        val pending =
            claimCommands.redeem(
                RedeemVoucherCommand(
                    TENANT_ID,
                    checkNotNull(allocated.oneTimeCode),
                    0,
                    "order-1",
                    RiskSignal.REVIEW,
                ),
            )
        val review = jdbc.foregroundTransaction { checkNotNull(reviews.findOpen(TENANT_ID, pending.claimId)) }

        val rejected =
            reviewCommands.reject(
                ReviewDecisionCommand(TENANT_ID, CAMPAIGN_ID, pending.claimId, review.id, 0, pending.revision),
            )

        rejected.claim.state shouldBeEqualTo ClaimState.ALLOCATED
        campaignSnapshot().allocatedCount shouldBeEqualTo 1
    }

    @Test
    fun `expired redemption review cannot be approved`() {
        createCampaign()
        val allocated = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))
        val pending =
            claimCommands.redeem(
                RedeemVoucherCommand(
                    TENANT_ID,
                    checkNotNull(allocated.oneTimeCode),
                    0,
                    "order-1",
                    RiskSignal.REVIEW,
                ),
            )
        val review = jdbc.foregroundTransaction { checkNotNull(reviews.findOpen(TENANT_ID, pending.claimId)) }
        configureCommandRuntime(serviceClock = Clock.fixed(NOW.plusSeconds(3_601), ZoneOffset.UTC))

        io.bluetape4k.assertions.assertFailsWith<VoucherCommandException> {
            reviewCommands.approve(
                ReviewDecisionCommand(TENANT_ID, CAMPAIGN_ID, pending.claimId, review.id, 0, pending.revision),
            )
        }.code shouldBeEqualTo VoucherCommandFailure.VOUCHER_EXPIRED
    }
}
