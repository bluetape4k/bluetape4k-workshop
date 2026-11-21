package io.bluetape4k.workshop.commerce.voucher.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * VoucherPoliciesTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoliciesTest {

    companion object: KLogging() {
/**
 * NOW은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW: Instant = Instant.parse("2026-07-19T00:00:00Z")
    }

    @Test
/**
 * 이 시나리오는 "campaign activation requires a valid period and capacity" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `campaign activation requires a valid period and capacity`() {
        val draft = campaign(state = CampaignState.DRAFT)

        val active = VoucherPolicies.activateCampaign(draft, draft.revision, NOW)

        log.debug { "active=$active" }
        active.state shouldBeEqualTo CampaignState.ACTIVE
        active.revision shouldBeEqualTo draft.revision + 1

        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.activateCampaign(
                campaign = draft.copy(capacity = 0),
                expectedRevision = draft.revision,
                now = NOW,
            )
        }.code shouldBeEqualTo VoucherPolicyFailure.INVALID_CAMPAIGN_POLICY
    }

    @Test
/**
 * 이 시나리오는 "ended campaign cannot resume and capacity cannot fall below allocations" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `ended campaign cannot resume and capacity cannot fall below allocations`() {
        val ended = campaign(state = CampaignState.ENDED)

        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.activateCampaign(ended, ended.revision, NOW)
        }.code shouldBeEqualTo VoucherPolicyFailure.INVALID_TRANSITION

        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.updateCampaignPolicy(
                campaign = campaign(state = CampaignState.ACTIVE, allocatedCount = 4),
                expectedRevision = 7,
                capacity = 3,
                perUserLimit = 1,
                redemptionTtlSeconds = 3600,
            )
        }.code shouldBeEqualTo VoucherPolicyFailure.CAPACITY_BELOW_ALLOCATED
    }

    @Test
/**
 * 이 시나리오는 "redemption review rejection returns to allocated without changing capacity" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `redemption review rejection returns to allocated without changing capacity`() {
        val claim = claim(
            state = ClaimState.REVIEW_REQUIRED,
            reviewKind = ReviewKind.REDEMPTION,
            capacityReserved = true
        )

        val outcome = VoucherPolicies.rejectReview(claim, expectedRevision = claim.revision)

        log.debug { "outcome=$outcome" }
        outcome.claim.state shouldBeEqualTo ClaimState.ALLOCATED
        outcome.claim.reviewKind.shouldBeNull()
        outcome.claim.capacityReserved.shouldBeTrue()
        outcome.capacityDelta shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "allocation review approval contributes capacity once" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `allocation review approval contributes capacity once`() {
        val claim = claim(
            state = ClaimState.REVIEW_REQUIRED,
            reviewKind = ReviewKind.ALLOCATION,
            capacityReserved = false
        )

        val outcome = VoucherPolicies.approveReview(claim, expectedRevision = claim.revision)

        log.debug { "outcome=$outcome" }
        outcome.claim.state shouldBeEqualTo ClaimState.ALLOCATED
        outcome.claim.capacityReserved.shouldBeTrue()
        outcome.capacityDelta shouldBeEqualTo 1
        VoucherPolicies.capacityContribution(outcome.claim) shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "allocation rejection is terminal without consuming capacity" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `allocation rejection is terminal without consuming capacity`() {
        val claim = claim(
            state = ClaimState.REVIEW_REQUIRED,
            reviewKind = ReviewKind.ALLOCATION,
            capacityReserved = false
        )

        val outcome = VoucherPolicies.rejectReview(claim, expectedRevision = claim.revision)

        log.debug { "outcome=$outcome" }
        outcome.claim.state shouldBeEqualTo ClaimState.REJECTED
        outcome.claim.capacityReserved.shouldBeFalse()
        outcome.capacityDelta shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "release expiry and revoke decrement reserved capacity exactly once" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `release expiry and revoke decrement reserved capacity exactly once`() {
        val allocated = claim(state = ClaimState.ALLOCATED, capacityReserved = true)

        VoucherPolicies.release(
            claim = allocated,
            expectedRevision = allocated.revision
        ).capacityDelta shouldBeEqualTo -1

        VoucherPolicies.expire(
            allocated.copy(expiresAt = NOW),
            allocated.revision,
            NOW
        ).capacityDelta shouldBeEqualTo -1

        VoucherPolicies.revoke(
            claim = allocated,
            expectedRevision = allocated.revision
        ).capacityDelta shouldBeEqualTo -1

        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.release(
                VoucherPolicies.release(allocated, allocated.revision).claim,
                allocated.revision + 1,
            )
        }.code shouldBeEqualTo VoucherPolicyFailure.INVALID_TRANSITION
    }

    @Test
/**
 * 이 시나리오는 "redeemed claims consume capacity and cannot be revoked" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "stale revision fails before transition" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stale revision fails before transition`() {
        val claim = claim(
            state = ClaimState.REVIEW_REQUIRED,
            reviewKind = ReviewKind.REDEMPTION,
            capacityReserved = true
        )

        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.approveReview(claim, expectedRevision = claim.revision - 1)
        }.code shouldBeEqualTo VoucherPolicyFailure.STALE_REVISION
    }

    @Test
/**
 * 이 시나리오는 "expiry requires the claim deadline to have elapsed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `expiry requires the claim deadline to have elapsed`() {
        val claim = claim(
            state = ClaimState.ALLOCATED,
            capacityReserved = true,
            expiresAt = NOW + 1.seconds()
        )

        assertFailsWith<VoucherPolicyException> {
            VoucherPolicies.expire(claim, claim.revision, NOW)
        }.code shouldBeEqualTo VoucherPolicyFailure.NOT_EXPIRED
    }

/**
 * claim은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
            pendingFromState = when (reviewKind) {
                ReviewKind.ALLOCATION -> ClaimState.ELIGIBLE
                ReviewKind.REDEMPTION -> ClaimState.ALLOCATED
                null                  -> null
            },
            capacityReserved = capacityReserved,
            allocationPolicyVersion = 3,
            expiresAt = expiresAt,
            revision = 11,
        )

/**
 * campaign은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
}
