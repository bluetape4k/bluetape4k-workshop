package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.domain.ReviewKind
import io.bluetape4k.workshop.commerce.voucher.domain.TransitionOutcome
import io.bluetape4k.workshop.commerce.voucher.domain.VoucherPolicies
import io.bluetape4k.workshop.commerce.voucher.domain.VoucherPolicyException
import io.bluetape4k.workshop.commerce.voucher.domain.VoucherPolicyFailure
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewStatus
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeService
import io.bluetape4k.workshop.commerce.voucher.security.VoucherLookupMaterial
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.*
/**
 * RedeemVoucherCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property code 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property redemptionReference 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property riskSignal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property claimId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class RedeemVoucherCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `redemptionReference`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val redemptionReference: String,
    /**
     * `riskSignal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val riskSignal: RiskSignal = RiskSignal.CLEAR,
    /**
     * `claimId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val claimId: UUID? = null,
)
/**
 * ClaimTransitionCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property claimId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class ClaimTransitionCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `claimId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val claimId: UUID,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
)

/**
 * campaign -> claim lock 순서 아래에서 redemption과 terminal transition을 적용합니다.
 * @property transactions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property campaigns 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property claims 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reviews 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audits 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property codes 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Service
internal class ClaimCommandService(
    /**
     * `transactions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactions: VoucherTransactionRunner,
    /**
     * `campaigns`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val campaigns: CampaignRepository,
    /**
     * `claims`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    private val claims: ClaimRepository,
    /**
     * `reviews`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val reviews: ReviewRepository,
    /**
     * `audits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val audits: AuditRepository,
    /**
     * `codes`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val codes: VoucherCodeService,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {
    companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val REDEMPTION_REFERENCE_DOMAIN = "voucher-redemption-reference-v1"
    }

    /**
     * `redeem`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = redeem(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun redeem(command: RedeemVoucherCommand): ClaimSnapshot {
        require(command.redemptionReference.length in 1..128) {
            "redemptionReference must contain 1..128 characters"
        }
        val lookup: VoucherLookupMaterial = codes.lookup(command.code)
            ?: fail(VoucherCommandFailure.INVALID_CODE)
        val now = Instant.now(clock)

        return transactions.foregroundTransaction {
            val candidate = claims.findByVerifier(command.tenantId, lookup.verifier)
                ?: fail(VoucherCommandFailure.INVALID_CODE)
            if (command.claimId != null && candidate.claimId != command.claimId) {
                fail(VoucherCommandFailure.CLAIM_NOT_FOUND)
            }
            val campaign = campaigns.findPublicForUpdate(command.tenantId, candidate.campaignId)
                ?: fail(VoucherCommandFailure.CAMPAIGN_NOT_FOUND)

            requireCampaignRedeemable(campaign)
            val claim = claims.findPublicForUpdate(command.tenantId, candidate.claimId)
                ?: fail(VoucherCommandFailure.CLAIM_NOT_FOUND)

            if (!codes.verify(
                    command.code,
                    checkNotNull(claim.codeVerifier),
                    checkNotNull(claim.verificationKeyVersion)
                )) {
                fail(VoucherCommandFailure.INVALID_CODE)
            }

            if (claim.state == ClaimState.REDEEMED) fail(VoucherCommandFailure.ALREADY_REDEEMED)
            if (claim.revision != command.expectedRevision) fail(VoucherCommandFailure.STALE_REVISION)

            val redemption: TransitionOutcome = policy {
                VoucherPolicies.redeem(claim.toSnapshot(), command.expectedRevision, now)
            }

            val transitioned = if (command.riskSignal == RiskSignal.REVIEW) {
                val snapshot = redemption.claim.copy(
                    state = ClaimState.REVIEW_REQUIRED,
                    reviewKind = ReviewKind.REDEMPTION,
                    pendingFromState = ClaimState.ALLOCATED,
                )
                TransitionOutcome(snapshot, capacityDelta = 0)
            } else {
                redemption
            }
            val updated = claim.apply(transitioned.claim)
                .copy(
                    redemptionReferenceDigest = digestHex(
                        REDEMPTION_REFERENCE_DOMAIN,
                        command.tenantId,
                        command.redemptionReference
                    ),
                )
            if (!claims.transition(updated, claim.revision)) {
                fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            }
            if (updated.state == ClaimState.REVIEW_REQUIRED) {
                openRedemptionReview(updated)
            }
            audits.append(updated.audit(if (updated.state == ClaimState.REVIEW_REQUIRED) "REDEMPTION_REVIEW_REQUIRED" else "REDEEMED"))

            log.debug { "voucher_claim_redeemed claimId=${updated.claimId} state=${updated.state}" }

            updated.toSnapshot()
        }
    }

    /**
     * `release`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = release(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun release(command: ClaimTransitionCommand): ClaimSnapshot =
        terminate(command, "RELEASED") { claim, expected, _ ->
            VoucherPolicies.release(claim, expected)
        }

    /**
     * `revoke`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = revoke(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun revoke(command: ClaimTransitionCommand): ClaimSnapshot =
        terminate(command, "REVOKED") { claim, expected, _ ->
            VoucherPolicies.revoke(claim, expected)
        }

    /**
     * `expire`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = expire(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun expire(command: ClaimTransitionCommand): ClaimSnapshot =
        terminate(command, "EXPIRED") { claim, expected, now ->
            VoucherPolicies.expire(claim, expected, now)
        }

    /**
     * terminate은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param reasonCode 업무 처리에 필요한 reasonCode 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param transition 업무 처리에 필요한 transition 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun terminate(
        command: ClaimTransitionCommand,
        reasonCode: String,
        transition: (ClaimSnapshot, Long, Instant) -> TransitionOutcome,
    ): ClaimSnapshot =
        transactions.foregroundTransaction {
            /**
             * `campaign`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val campaign = campaigns.findPublicForUpdate(command.tenantId, command.campaignId)
                ?: fail(VoucherCommandFailure.CAMPAIGN_NOT_FOUND)
            /**
             * `claim`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val claim = claims.findPublicForUpdate(command.tenantId, command.claimId)
                ?: fail(VoucherCommandFailure.CLAIM_NOT_FOUND)
            if (claim.campaignId != campaign.campaignId) {
                fail(VoucherCommandFailure.CLAIM_NOT_FOUND)
            }
            if (claim.revision != command.expectedRevision) {
                fail(VoucherCommandFailure.STALE_REVISION)
            }
            /**
             * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
             *
             * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
             */
            val outcome = policy {
                transition(claim.toSnapshot(), command.expectedRevision, Instant.now(clock))
            }
            if (
                outcome.capacityDelta == -1 &&
                !campaigns.tryRelease(
                    campaign.tenantId,
                    campaign.id,
                    campaign.revision
                )
            ) {
                fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            }
            /**
             * `updated`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val updated = claim.apply(outcome.claim)
            if (!claims.transition(updated, claim.revision)) {
                fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            }
            audits.append(updated.audit(reasonCode))

            log.debug { "voucher_claim_terminated claimId=${updated.claimId} state=${updated.state}" }
            updated.toSnapshot()
        }

    /**
     * openRedemptionReview은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun openRedemptionReview(claim: ClaimRecord) {
        reviews.insert(
            ReviewRecord(
                id = 0,
                tenantId = claim.tenantId,
                campaignId = claim.campaignId,
                claimRowId = claim.id,
                claimId = claim.claimId,
                kind = ReviewKind.REDEMPTION,
                status = ReviewStatus.OPEN,
                reasonCode = "RISK_REVIEW",
                signalSummary = "risk=review",
                reviewerActorDigest = null,
                expectedClaimRevision = claim.revision,
                revision = 0,
            ),
        )
    }
}

/**
 * apply은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param snapshot 업무 처리에 필요한 snapshot 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun ClaimRecord.apply(snapshot: ClaimSnapshot): ClaimRecord =
    copy(
        state = snapshot.state,
        reviewKind = snapshot.reviewKind,
        pendingFromState = snapshot.pendingFromState,
        capacityReserved = snapshot.capacityReserved,
        expiresAt = snapshot.expiresAt,
        revision = snapshot.revision,
    )

/**
 * policy은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal inline fun <T> policy(block: () -> T): T =
    try {
        block()
    } catch (failure: VoucherPolicyException) {
        when (failure.code) {
            VoucherPolicyFailure.STALE_REVISION -> fail(VoucherCommandFailure.STALE_REVISION)
            VoucherPolicyFailure.VOUCHER_EXPIRED -> fail(VoucherCommandFailure.VOUCHER_EXPIRED)
            else                                -> fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
        }
    }

/**
 * requireCampaignRedeemable은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
 * 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun requireCampaignRedeemable(campaign: CampaignRecord) {
    when (campaign.state) {
        CampaignState.DRAFT  -> fail(VoucherCommandFailure.CAMPAIGN_NOT_ACTIVE)
        CampaignState.PAUSED -> fail(VoucherCommandFailure.CAMPAIGN_PAUSED)
        CampaignState.ACTIVE, CampaignState.ENDED -> Unit
    }
}
