package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.ISO_8859_1

internal class AllocationServiceTest : VoucherCommandTestSupport() {
    @Test
    fun `immediate allocation reserves one capacity and returns one opaque code`() {
        createCampaign()

        val result = allocation.allocate(command("user-1"))

        result.claim.state shouldBeEqualTo ClaimState.ALLOCATED
        result.oneTimeCode?.startsWith("V7-") shouldBeEqualTo true
        campaignSnapshot().allocatedCount shouldBeEqualTo 1
        val persisted = jdbc.foregroundTransaction { checkNotNull(claims.findPublic(TENANT_ID, result.claim.claimId)) }
        String(checkNotNull(persisted.codeVerifier), ISO_8859_1) shouldNotBeEqualTo result.oneTimeCode
    }

    @Test
    fun `review signal opens allocation review without reserving capacity`() {
        createCampaign()

        val result = allocation.allocate(command("user-1", RiskSignal.REVIEW))

        result.claim.state shouldBeEqualTo ClaimState.REVIEW_REQUIRED
        result.oneTimeCode.shouldBeNull()
        campaignSnapshot().allocatedCount shouldBeEqualTo 0
        jdbc.foregroundTransaction { reviews.findOpen(TENANT_ID, result.claim.claimId) }?.reasonCode shouldBeEqualTo
            "RISK_REVIEW"
    }

    @Test
    fun `same user and capacity limits are authoritative in PostgreSQL`() {
        createCampaign(capacity = 1, perUserLimit = 1)
        allocation.allocate(command("user-1"))

        assertFailsWith<VoucherCommandException> { allocation.allocate(command("user-1")) }.code shouldBeEqualTo
            VoucherCommandFailure.PER_USER_LIMIT_REACHED
        assertFailsWith<VoucherCommandException> { allocation.allocate(command("user-2")) }.code shouldBeEqualTo
            VoucherCommandFailure.CAPACITY_EXHAUSTED
        campaignSnapshot().allocatedCount shouldBeEqualTo 1
    }

    private fun command(
        userRef: String,
        riskSignal: RiskSignal = RiskSignal.CLEAR,
    ) = AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, userRef, riskSignal)
}
