package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import org.junit.jupiter.api.Test

internal class CampaignCommandServiceTest : VoucherCommandTestSupport() {
    @Test
    fun `pause and resume use an authoritative campaign revision`() {
        createCampaign()

        val paused = campaignCommands.pause(CampaignTransitionCommand(TENANT_ID, CAMPAIGN_ID, 0))
        paused.state shouldBeEqualTo CampaignState.PAUSED

        assertFailsWith<VoucherCommandException> {
            campaignCommands.activate(CampaignTransitionCommand(TENANT_ID, CAMPAIGN_ID, 0))
        }.code shouldBeEqualTo VoucherCommandFailure.STALE_REVISION

        campaignCommands.activate(CampaignTransitionCommand(TENANT_ID, CAMPAIGN_ID, paused.revision))
            .state shouldBeEqualTo CampaignState.ACTIVE
    }

    @Test
    fun `policy update cannot reduce capacity below allocated claims`() {
        createCampaign(capacity = 2)
        allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))

        assertFailsWith<VoucherCommandException> {
            campaignCommands.updatePolicy(
                CampaignPolicyCommand(TENANT_ID, CAMPAIGN_ID, 1, 0, 1, 3_600),
            )
        }.code shouldBeEqualTo VoucherCommandFailure.CONCURRENT_MODIFICATION
    }
}
