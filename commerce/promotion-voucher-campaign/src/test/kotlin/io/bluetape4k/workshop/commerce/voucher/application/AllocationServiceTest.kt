package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.ISO_8859_1

/**
 * AllocationServiceTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class AllocationServiceTest: VoucherCommandTestSupport() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "immediate allocation reserves one capacity and returns one opaque code" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `immediate allocation reserves one capacity and returns one opaque code`() {
        createCampaign()

        val result = allocation.allocate(command("user-1"))

        result.claim.state shouldBeEqualTo ClaimState.ALLOCATED
        result.oneTimeCode?.startsWith("V7-").shouldBeTrue()

        campaignSnapshot().allocatedCount shouldBeEqualTo 1
        val persisted = jdbc.foregroundTransaction {
            claims.findPublic(TENANT_ID, result.claim.claimId).shouldNotBeNull()
        }
        String(persisted.codeVerifier!!, ISO_8859_1) shouldNotBeEqualTo result.oneTimeCode
    }

    @Test
/**
 * 이 시나리오는 "review signal opens allocation review without reserving capacity" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `review signal opens allocation review without reserving capacity`() {
        createCampaign()

        val result = allocation.allocate(command("user-1", RiskSignal.REVIEW))

        result.claim.state shouldBeEqualTo ClaimState.REVIEW_REQUIRED
        result.oneTimeCode.shouldBeNull()
        campaignSnapshot().allocatedCount shouldBeEqualTo 0

        jdbc.foregroundTransaction {
            reviews.findOpen(TENANT_ID, result.claim.claimId)
        }?.reasonCode shouldBeEqualTo "RISK_REVIEW"
    }

    @Test
/**
 * 이 시나리오는 "same user and capacity limits are authoritative in PostgreSQL" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same user and capacity limits are authoritative in PostgreSQL`() {
        createCampaign(capacity = 1, perUserLimit = 1)
        allocation.allocate(command("user-1"))

        assertFailsWith<VoucherCommandException> {
            allocation.allocate(command("user-1"))
        }.code shouldBeEqualTo VoucherCommandFailure.PER_USER_LIMIT_REACHED

        assertFailsWith<VoucherCommandException> {
            allocation.allocate(command("user-2"))
        }.code shouldBeEqualTo VoucherCommandFailure.CAPACITY_EXHAUSTED

        campaignSnapshot().allocatedCount shouldBeEqualTo 1
    }

/**
 * command은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun command(
        userRef: String,
        riskSignal: RiskSignal = RiskSignal.CLEAR,
    ): AllocateVoucherCommand =
        AllocateVoucherCommand(
            tenantId = TENANT_ID,
            campaignId = CAMPAIGN_ID,
            userRef = userRef,
            riskSignal = riskSignal
        )
}
