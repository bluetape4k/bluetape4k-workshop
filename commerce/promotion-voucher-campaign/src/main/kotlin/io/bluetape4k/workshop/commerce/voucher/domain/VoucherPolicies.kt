package io.bluetape4k.workshop.commerce.voucher.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.time.Instant

/**
 * `VoucherPolicyFailure`은 실패·오류 분류 코드입니다.
 * provider 또는 실행 정책을 선택하는 값이며 테스트·운영 시나리오의 분기 기준입니다.
 */
internal enum class VoucherPolicyFailure {
    /**
     * `VoucherPolicyFailure.STALE_REVISION`은 호출자가 가진 revision이 저장된 최신 revision보다 오래되어 반영할 수 없는
     * 값입니다.
     */
    STALE_REVISION,
    /**
     * `VoucherPolicyFailure.INVALID_TRANSITION`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_TRANSITION,
    /**
     * `VoucherPolicyFailure.INVALID_CAMPAIGN_POLICY`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_CAMPAIGN_POLICY,
    /**
     * `VoucherPolicyFailure.CAPACITY_BELOW_ALLOCATED`은 `capacity below allocated` 업무 상황을 나타냅니다.
     * provider
     * 또는 실행 시나리오를 선택하며 실제 업무 상태와 혼동하지 않습니다.
     */
    CAPACITY_BELOW_ALLOCATED,
    /**
     * `VoucherPolicyFailure.NOT_EXPIRED`은 유효기간·lease·hold가 지나 자동 만료된 값입니다.
     */
    NOT_EXPIRED,
    /**
     * `VoucherPolicyFailure.VOUCHER_EXPIRED`은 유효기간·lease·hold가 지나 자동 만료된 값입니다.
     */
    VOUCHER_EXPIRED,
}
/**
 * VoucherPolicyException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property code 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


internal class VoucherPolicyException(
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: VoucherPolicyFailure,
): RuntimeException(code.name)

/** HTTP command, review, reconciliation worker가 공유하는 순수 transition policy입니다. */
/** HTTP command, review, reconciliation worker가 공유하는 순수 transition policy입니다. */
internal object VoucherPolicies: KLogging() {
    
    /**
     * activateCampaign은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun activateCampaign(
        campaign: CampaignSnapshot,
        expectedRevision: Long,
        now: Instant,
    ): CampaignSnapshot {
        requireRevision(campaign.revision, expectedRevision)
        if (campaign.state != CampaignState.DRAFT && campaign.state != CampaignState.PAUSED) {
            fail(VoucherPolicyFailure.INVALID_TRANSITION)
        }
        validateCampaignPolicy(campaign, now)
        return campaign.copy(state = CampaignState.ACTIVE, revision = campaign.revision + 1)
    }

    /**
     * pauseCampaign은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun pauseCampaign(
        campaign: CampaignSnapshot,
        expectedRevision: Long,
    ): CampaignSnapshot {
        requireRevision(campaign.revision, expectedRevision)
        if (campaign.state != CampaignState.ACTIVE) fail(VoucherPolicyFailure.INVALID_TRANSITION)
        return campaign.copy(state = CampaignState.PAUSED, revision = campaign.revision + 1)
    }

    /**
     * endCampaign은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun endCampaign(
        campaign: CampaignSnapshot,
        expectedRevision: Long,
    ): CampaignSnapshot {
        requireRevision(campaign.revision, expectedRevision)
        if (campaign.state != CampaignState.ACTIVE && campaign.state != CampaignState.PAUSED) {
            fail(VoucherPolicyFailure.INVALID_TRANSITION)
        }
        return campaign.copy(state = CampaignState.ENDED, revision = campaign.revision + 1)
    }

    /**
     * updateCampaignPolicy은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param capacity 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     * @param perUserLimit 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     * @param redemptionTtlSeconds 업무 처리에 필요한 redemptionTtlSeconds 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야
     * 합니다.
     */
    fun updateCampaignPolicy(
        campaign: CampaignSnapshot,
        expectedRevision: Long,
        capacity: Int,
        perUserLimit: Int,
        redemptionTtlSeconds: Long,
    ): CampaignSnapshot {
        requireRevision(campaign.revision, expectedRevision)
        if (campaign.state == CampaignState.ENDED) fail(VoucherPolicyFailure.INVALID_TRANSITION)
        if (capacity < campaign.allocatedCount) fail(VoucherPolicyFailure.CAPACITY_BELOW_ALLOCATED)
        if (capacity <= 0 || perUserLimit <= 0 || redemptionTtlSeconds <= 0) {
            fail(VoucherPolicyFailure.INVALID_CAMPAIGN_POLICY)
        }
        return campaign.copy(
            capacity = capacity,
            perUserLimit = perUserLimit,
            redemptionTtlSeconds = redemptionTtlSeconds,
            policyVersion = campaign.policyVersion + 1,
            revision = campaign.revision + 1,
        )
    }

    /**
     * approveReview은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun approveReview(
        claim: ClaimSnapshot,
        expectedRevision: Long,
    ): TransitionOutcome {
        requireReview(claim, expectedRevision)

        return when (claim.reviewKind) {
            ReviewKind.ALLOCATION -> transition(claim, ClaimState.ALLOCATED, capacityReserved = true, capacityDelta = 1)
            ReviewKind.REDEMPTION -> transition(claim, ClaimState.REDEEMED, capacityReserved = true, capacityDelta = 0)
            null -> fail(VoucherPolicyFailure.INVALID_TRANSITION)
        }
    }

    /**
     * rejectReview은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun rejectReview(
        claim: ClaimSnapshot,
        expectedRevision: Long,
    ): TransitionOutcome {
        requireReview(claim, expectedRevision)

        return when (claim.reviewKind) {
            ReviewKind.ALLOCATION -> transition(claim, ClaimState.REJECTED, capacityReserved = false, capacityDelta = 0)
            ReviewKind.REDEMPTION -> transition(claim, ClaimState.ALLOCATED, capacityReserved = true, capacityDelta = 0)
            null -> fail(VoucherPolicyFailure.INVALID_TRANSITION)
        }
    }

    /**
     * redeem은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun redeem(
        claim: ClaimSnapshot,
        expectedRevision: Long,
        now: Instant,
    ): TransitionOutcome {
        requireRevision(claim.revision, expectedRevision)
        if (claim.state != ClaimState.ALLOCATED || !claim.capacityReserved) {
            fail(VoucherPolicyFailure.INVALID_TRANSITION)
        }
        if (claim.expiresAt?.let { !now.isBefore(it) } == true) {
            fail(VoucherPolicyFailure.VOUCHER_EXPIRED)
        }
        return transition(claim, ClaimState.REDEEMED, capacityReserved = true, capacityDelta = 0)
    }

    /**
     * release은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun release(
        claim: ClaimSnapshot,
        expectedRevision: Long,
    ): TransitionOutcome =
        terminateReserved(claim, expectedRevision, ClaimState.RELEASED)

    /**
     * expire은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun expire(
        claim: ClaimSnapshot,
        expectedRevision: Long,
        now: Instant,
    ): TransitionOutcome {
        if (claim.expiresAt?.let(now::isBefore) != false) {
            fail(VoucherPolicyFailure.NOT_EXPIRED)
        }
        return terminateReserved(claim, expectedRevision, ClaimState.EXPIRED)
    }

    /**
     * revoke은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun revoke(
        claim: ClaimSnapshot,
        expectedRevision: Long,
    ): TransitionOutcome = terminateReserved(claim, expectedRevision, ClaimState.REVOKED)

    /**
     * capacityContribution은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun capacityContribution(claim: ClaimSnapshot): Int =
        if (
            claim.capacityReserved &&
            claim.state in setOf(ClaimState.ALLOCATED, ClaimState.REVIEW_REQUIRED, ClaimState.REDEEMED)
        ) {
            1
        } else {
            0
        }

    /**
     * terminateReserved은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param terminalState 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun terminateReserved(
        claim: ClaimSnapshot,
        expectedRevision: Long,
        terminalState: ClaimState,
    ): TransitionOutcome {
        requireRevision(claim.revision, expectedRevision)
        val releasable =
            claim.state == ClaimState.ALLOCATED ||
                    (claim.state == ClaimState.REVIEW_REQUIRED && claim.reviewKind == ReviewKind.REDEMPTION)
        if (!releasable || !claim.capacityReserved) fail(VoucherPolicyFailure.INVALID_TRANSITION)
        return transition(claim, terminalState, capacityReserved = false, capacityDelta = -1)
    }

    /**
     * requireReview은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun requireReview(
        claim: ClaimSnapshot,
        expectedRevision: Long,
    ) {
        requireRevision(claim.revision, expectedRevision)
        if (claim.state != ClaimState.REVIEW_REQUIRED) fail(VoucherPolicyFailure.INVALID_TRANSITION)
    }

    /**
     * transition은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param capacityReserved 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     * @param capacityDelta 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    private fun transition(
        claim: ClaimSnapshot,
        state: ClaimState,
        capacityReserved: Boolean,
        capacityDelta: Int,
    ): TransitionOutcome =
        TransitionOutcome(
            claim =
                claim.copy(
                    state = state,
                    reviewKind = null,
                    pendingFromState = null,
                    capacityReserved = capacityReserved,
                    revision = claim.revision + 1,
                ),
            capacityDelta = capacityDelta,
        )

    /**
     * validateCampaignPolicy은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun validateCampaignPolicy(
        campaign: CampaignSnapshot,
        now: Instant,
    ) {
        if (
            campaign.capacity <= 0 ||
            campaign.perUserLimit <= 0 ||
            campaign.redemptionTtlSeconds <= 0 ||
            !campaign.startsAt.isBefore(campaign.endsAt) ||
            !now.isBefore(campaign.endsAt)
        ) {
            fail(VoucherPolicyFailure.INVALID_CAMPAIGN_POLICY)
        }
    }

    /**
     * requireRevision은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param actual 업무 처리에 필요한 actual 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expected 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun requireRevision(
        actual: Long,
        expected: Long,
    ) {
        if (actual != expected) fail(VoucherPolicyFailure.STALE_REVISION)
    }

    /**
     * fail은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun fail(code: VoucherPolicyFailure): Nothing {
        log.debug { "voucher_policy_rejected code=$code" }
        throw VoucherPolicyException(code)
    }
}
