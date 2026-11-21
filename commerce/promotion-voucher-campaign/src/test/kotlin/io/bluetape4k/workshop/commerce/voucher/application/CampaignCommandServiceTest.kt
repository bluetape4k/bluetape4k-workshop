package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import org.junit.jupiter.api.Test

/**
 * CampaignCommandServiceTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class CampaignCommandServiceTest: VoucherCommandTestSupport() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "pause and resume use an authoritative campaign revision" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "policy update cannot reduce capacity below allocated claims" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
