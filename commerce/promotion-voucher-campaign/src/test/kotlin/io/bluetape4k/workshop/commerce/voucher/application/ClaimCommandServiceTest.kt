package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset

internal class ClaimCommandServiceTest : VoucherCommandTestSupport() {
    @Test
    fun `redemption changes allocated claim once without changing capacity`() {
        createCampaign()
        val allocated = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))

        val redeemed =
            claimCommands.redeem(
                RedeemVoucherCommand(
                    tenantId = TENANT_ID,
                    code = checkNotNull(allocated.oneTimeCode),
                    expectedRevision = allocated.claim.revision,
                    redemptionReference = "order-1",
                ),
            )

        redeemed.state shouldBeEqualTo ClaimState.REDEEMED
        campaignSnapshot().allocatedCount shouldBeEqualTo 1
        assertFailsWith<VoucherCommandException> {
            claimCommands.redeem(
                RedeemVoucherCommand(TENANT_ID, checkNotNull(allocated.oneTimeCode), redeemed.revision, "order-2"),
            )
        }.code shouldBeEqualTo VoucherCommandFailure.ALREADY_REDEEMED
    }

    @Test
    fun `release and revoke decrement capacity exactly once`() {
        createCampaign(capacity = 2)
        val first = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))
        val second = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-2"))

        claimCommands.release(ClaimTransitionCommand(TENANT_ID, CAMPAIGN_ID, first.claim.claimId, 0))
            .state shouldBeEqualTo ClaimState.RELEASED
        claimCommands.revoke(ClaimTransitionCommand(TENANT_ID, CAMPAIGN_ID, second.claim.claimId, 0))
            .state shouldBeEqualTo ClaimState.REVOKED

        campaignSnapshot().allocatedCount shouldBeEqualTo 0
    }

    @Test
    fun `redemption review keeps capacity reserved`() {
        createCampaign()
        val allocated = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))

        val review =
            claimCommands.redeem(
                RedeemVoucherCommand(
                    TENANT_ID,
                    checkNotNull(allocated.oneTimeCode),
                    allocated.claim.revision,
                    "order-1",
                    RiskSignal.REVIEW,
                ),
            )

        review.state shouldBeEqualTo ClaimState.REVIEW_REQUIRED
        campaignSnapshot().allocatedCount shouldBeEqualTo 1
    }

    @Test
    fun `ended campaign keeps an issued voucher redeemable until claim expiry`() {
        createCampaign()
        val allocated = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))
        campaignCommands.end(CampaignTransitionCommand(TENANT_ID, CAMPAIGN_ID, expectedRevision = 1))

        claimCommands.redeem(
            RedeemVoucherCommand(TENANT_ID, checkNotNull(allocated.oneTimeCode), 0, "order-ended"),
        )
            .state shouldBeEqualTo ClaimState.REDEEMED
    }

    @Test
    fun `released voucher cannot enter redemption review`() {
        createCampaign()
        val allocated = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))
        claimCommands.release(ClaimTransitionCommand(TENANT_ID, CAMPAIGN_ID, allocated.claim.claimId, 0))

        assertFailsWith<VoucherCommandException> {
            claimCommands.redeem(
                RedeemVoucherCommand(
                    TENANT_ID,
                    checkNotNull(allocated.oneTimeCode),
                    1,
                    "order-released",
                    RiskSignal.REVIEW,
                ),
            )
        }.code shouldBeEqualTo VoucherCommandFailure.CONCURRENT_MODIFICATION
    }

    @Test
    fun `expiry releases reserved capacity after the claim ttl`() {
        createCampaign()
        val allocated = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))
        configureCommandRuntime(serviceClock = Clock.fixed(NOW.plusSeconds(3_601), ZoneOffset.UTC))

        claimCommands.expire(ClaimTransitionCommand(TENANT_ID, CAMPAIGN_ID, allocated.claim.claimId, 0))
            .state shouldBeEqualTo ClaimState.EXPIRED
        campaignSnapshot().allocatedCount shouldBeEqualTo 0
    }

}
