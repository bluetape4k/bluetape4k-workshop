package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import org.junit.jupiter.api.Test

internal class CampaignCommandServiceTest: VoucherCommandTestSupport() {

    companion object: KLogging()

    @Test
    fun `pause and resume use an authoritative campaign revision`() {
        createCampaign()

        val paused = campaignCommands.pause(
            CampaignTransitionCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                expectedRevision = 0
            )
        )
        paused.state shouldBeEqualTo CampaignState.PAUSED

        assertFailsWith<VoucherCommandException> {
            campaignCommands.activate(
                CampaignTransitionCommand(
                    tenantId = TENANT_ID,
                    campaignId = CAMPAIGN_ID,
                    expectedRevision = 0
                )
            )
        }.code shouldBeEqualTo VoucherCommandFailure.STALE_REVISION

        campaignCommands.activate(
            CampaignTransitionCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                expectedRevision = paused.revision
            )
        ).state shouldBeEqualTo CampaignState.ACTIVE
    }

    @Test
    fun `policy update cannot reduce capacity below allocated claims`() {
        createCampaign(capacity = 2)

        allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-1"
            )
        )

        assertFailsWith<VoucherCommandException> {
            campaignCommands.updatePolicy(
                CampaignPolicyCommand(
                    tenantId = TENANT_ID,
                    campaignId = CAMPAIGN_ID,
                    expectedRevision = 1,
                    capacity = 0,
                    perUserLimit = 1,
                    redemptionTtlSeconds = 3_600
                ),
            )
        }.code shouldBeEqualTo VoucherCommandFailure.CONCURRENT_MODIFICATION
    }
}
