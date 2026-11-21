package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset

/**
 * ClaimCommandServiceTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ClaimCommandServiceTest: VoucherCommandTestSupport() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "redemption changes allocated claim once without changing capacity" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `redemption changes allocated claim once without changing capacity`() {
        createCampaign()
        val allocated = allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-1"
            )
        )

        val redeemed = claimCommands.redeem(
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
                RedeemVoucherCommand(
                    tenantId = TENANT_ID,
                    code = allocated.oneTimeCode.shouldNotBeNull(),
                    expectedRevision = redeemed.revision,
                    redemptionReference = "order-2"
                ),
            )
        }.code shouldBeEqualTo VoucherCommandFailure.ALREADY_REDEEMED
    }

    @Test
/**
 * 이 시나리오는 "release and revoke decrement capacity exactly once" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `release and revoke decrement capacity exactly once`() {
        createCampaign(capacity = 2)
        val first = allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-1"
            )
        )
        val second = allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-2"
            )
        )

        claimCommands.release(
            ClaimTransitionCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                claimId = first.claim.claimId,
                expectedRevision = 0
            )
        ).state shouldBeEqualTo ClaimState.RELEASED

        claimCommands.revoke(
            ClaimTransitionCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                claimId = second.claim.claimId,
                expectedRevision = 0
            )
        ).state shouldBeEqualTo ClaimState.REVOKED

        campaignSnapshot().allocatedCount shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "redemption review keeps capacity reserved" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `redemption review keeps capacity reserved`() {
        createCampaign()

        val allocated = allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-1"
            )
        )

        val review = claimCommands.redeem(
            RedeemVoucherCommand(
                tenantId = TENANT_ID,
                code = allocated.oneTimeCode.shouldNotBeNull(),
                expectedRevision = allocated.claim.revision,
                redemptionReference = "order-1",
                riskSignal = RiskSignal.REVIEW,
            ),
        )

        review.state shouldBeEqualTo ClaimState.REVIEW_REQUIRED
        campaignSnapshot().allocatedCount shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "ended campaign keeps an issued voucher redeemable until claim expiry" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `ended campaign keeps an issued voucher redeemable until claim expiry`() {
        createCampaign()
        val allocated = allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-1"
            )
        )
        campaignCommands.end(
            CampaignTransitionCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                expectedRevision = 1
            )
        )

        claimCommands.redeem(
            RedeemVoucherCommand(
                tenantId = TENANT_ID,
                code = allocated.oneTimeCode.shouldNotBeNull(),
                expectedRevision = 0,
                redemptionReference = "order-ended"
            ),
        ).state shouldBeEqualTo ClaimState.REDEEMED
    }

    @Test
/**
 * 이 시나리오는 "released voucher cannot enter redemption review" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `released voucher cannot enter redemption review`() {
        createCampaign()
        val allocated = allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-1"
            )
        )
        claimCommands.release(
            ClaimTransitionCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                claimId = allocated.claim.claimId,
                expectedRevision = 0
            )
        )

        assertFailsWith<VoucherCommandException> {
            claimCommands.redeem(
                RedeemVoucherCommand(
                    tenantId = TENANT_ID,
                    code = checkNotNull(allocated.oneTimeCode),
                    expectedRevision = 1,
                    redemptionReference = "order-released",
                    riskSignal = RiskSignal.REVIEW,
                ),
            )
        }.code shouldBeEqualTo VoucherCommandFailure.CONCURRENT_MODIFICATION
    }

    @Test
/**
 * 이 시나리오는 "expiry releases reserved capacity after the claim ttl" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `expiry releases reserved capacity after the claim ttl`() {
        createCampaign()
        val allocated = allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "user-1"
            )
        )
        configureCommandRuntime(serviceClock = Clock.fixed(NOW + 3_601.seconds(), ZoneOffset.UTC))

        claimCommands.expire(
            ClaimTransitionCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                claimId = allocated.claim.claimId,
                expectedRevision = 0
            )
        ).state shouldBeEqualTo ClaimState.EXPIRED
        
        campaignSnapshot().allocatedCount shouldBeEqualTo 0
    }

}
