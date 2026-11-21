package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.domain.ReviewKind
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRecord
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
import io.bluetape4k.workshop.commerce.voucher.security.VoucherGenerationInput
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.*

/**
 * `RiskSignal`은 업무 분기용 제한된 값 집합입니다.
 * 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행하는 명령·신호 계약입니다.
 */
internal enum class RiskSignal {
    /**
     * `RiskSignal.CLEAR`은 `clear` 업무 상황을 나타냅니다. 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    CLEAR,
    /**
     * `RiskSignal.REVIEW`은 `review` 업무 상황을 나타냅니다. 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    REVIEW,
    /**
     * `RiskSignal.UNKNOWN`은 외부 결과가 아직 확정되지 않아 성공이나 실패로 단정할 수 없는 값입니다.
     */
    UNKNOWN,
}

/**
 * `VoucherCommandFailure`은 실패·오류 분류 코드입니다.
 * 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행하는 명령·신호 계약입니다.
 */
internal enum class VoucherCommandFailure {
    /**
     * `VoucherCommandFailure.CAMPAIGN_ALREADY_EXISTS`은 `campaign already exists` 업무 상황을 나타냅니다. 현재
     * 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    CAMPAIGN_ALREADY_EXISTS,
    /**
     * `VoucherCommandFailure.CAMPAIGN_NOT_FOUND`은 요청한 업무 대상이 존재하지 않아 side effect를 실행하지 않은 값입니다.
     */
    CAMPAIGN_NOT_FOUND,
    /**
     * `VoucherCommandFailure.CLAIM_NOT_FOUND`은 요청한 업무 대상이 존재하지 않아 side effect를 실행하지 않은 값입니다.
     */
    CLAIM_NOT_FOUND,
    /**
     * `VoucherCommandFailure.REVIEW_NOT_FOUND`은 요청한 업무 대상이 존재하지 않아 side effect를 실행하지 않은 값입니다.
     */
    REVIEW_NOT_FOUND,
    /**
     * `VoucherCommandFailure.CAMPAIGN_PAUSED`은 운영자가 campaign 신규 처리를 일시 중지한 값입니다.
     */
    CAMPAIGN_PAUSED,
    /**
     * `VoucherCommandFailure.CAMPAIGN_NOT_ACTIVE`은 campaign이 활성 상태가 아니어서 고객 명령을 허용할 수 없는 값입니다.
     */
    CAMPAIGN_NOT_ACTIVE,
    /**
     * `VoucherCommandFailure.CAMPAIGN_NOT_STARTED`은 `campaign not started` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate
     * revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    CAMPAIGN_NOT_STARTED,
    /**
     * `VoucherCommandFailure.CAMPAIGN_ENDED`은 `campaign ended` 업무 상황을 나타냅니다. 현재 상태와 aggregate
     * revision을
     * 검증한 뒤 허용된 전이만 실행합니다.
     */
    CAMPAIGN_ENDED,
    /**
     * `VoucherCommandFailure.CAPACITY_EXHAUSTED`은 재시도·용량·보존 예산을 소진해 자동 처리를 멈춘 값입니다.
     */
    CAPACITY_EXHAUSTED,
    /**
     * `VoucherCommandFailure.PER_USER_LIMIT_REACHED`은 `per user limit reached` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    PER_USER_LIMIT_REACHED,
    /**
     * `VoucherCommandFailure.INVALID_CODE`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_CODE,
    /**
     * `VoucherCommandFailure.VOUCHER_EXPIRED`은 유효기간·lease·hold가 지나 자동 만료된 값입니다.
     */
    VOUCHER_EXPIRED,
    /**
     * `VoucherCommandFailure.ALREADY_REDEEMED`은 `already redeemed` 업무 상황을 나타냅니다. 현재 상태와 aggregate
     * revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    ALREADY_REDEEMED,
    /**
     * `VoucherCommandFailure.STALE_REVISION`은 호출자가 가진 revision이 저장된 최신 revision보다 오래되어 반영할 수 없는
     * 값입니다.
     */
    STALE_REVISION,
    /**
     * `VoucherCommandFailure.CONCURRENT_MODIFICATION`은 `concurrent modification` 업무 상황을 나타냅니다. 현재
     * 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    CONCURRENT_MODIFICATION,
    /**
     * `VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE`은 필수 의존성이 준비되지 않아 요청을 수용할 수 없는 값입니다.
     */
    REPLAY_KEY_UNAVAILABLE,
    /**
     * `VoucherCommandFailure.CODE_ALREADY_ACKNOWLEDGED`은 `code already acknowledged` 업무 상황을 나타냅니다.
     * 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    CODE_ALREADY_ACKNOWLEDGED,
}
/**
 * VoucherCommandException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property code 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


internal class VoucherCommandException(
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: VoucherCommandFailure,
): RuntimeException(code.name)
/**
 * AllocateVoucherCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property userRef 호출 주체를 나타내는 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보나 비밀값은 저장·로그에 남기지 않습니다.
 * @property riskSignal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class AllocateVoucherCommand(
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
     * `userRef`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val userRef: String,
    /**
     * `riskSignal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val riskSignal: RiskSignal = RiskSignal.CLEAR,
)
/**
 * AllocationResult은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property claim 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property allocationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property oneTimeCode 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property reviewId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class AllocationResult(
    /**
     * `claim`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val claim: ClaimSnapshot,
    /**
     * `allocationId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val allocationId: UUID,
    /**
     * `oneTimeCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val oneTimeCode: String?,
    /**
     * `reviewId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val reviewId: Long?,
)

/**
 * canonical campaign row lock 아래에서 allocated 또는 review-required claim을 생성합니다.
 * @property transactions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property campaigns 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property claims 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reviews 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audits 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property codes 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Service
internal class AllocationService(
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
    companion object: KLogging()

    /**
     * `allocate`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = allocate(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun allocate(command: AllocateVoucherCommand): AllocationResult {
        validate(command)
        val claimId = Uuid.V7.nextId()
        val allocationId = Uuid.V7.nextId()
        val issued = codes.issue(VoucherGenerationInput(command.tenantId, command.campaignId, allocationId))
        val now = Instant.now(clock)
        val userDigest = voucherUserDigest(command.tenantId, command.userRef)

        return transactions.foregroundTransaction {
            val campaign =
                campaigns.findPublicForUpdate(command.tenantId, command.campaignId)
                    ?: fail(VoucherCommandFailure.CAMPAIGN_NOT_FOUND)
            requireAllocatable(campaign, now)
            if (claims.countForUser(command.tenantId, command.campaignId, userDigest) >= campaign.perUserLimit) {
                fail(VoucherCommandFailure.PER_USER_LIMIT_REACHED)
            }

            val reviewRequired = command.riskSignal == RiskSignal.REVIEW
            if (!reviewRequired && campaign.allocatedCount >= campaign.capacity) {
                fail(VoucherCommandFailure.CAPACITY_EXHAUSTED)
            }
            if (!reviewRequired && !campaigns.tryReserve(campaign.tenantId, campaign.id, campaign.revision)) {
                fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            }

            val claim: ClaimRecord = claims.insert(
                ClaimRecord(
                    id = 0,
                    tenantId = command.tenantId,
                    campaignRowId = campaign.id,
                    campaignId = campaign.campaignId,
                    claimId = claimId,
                    allocationId = allocationId,
                    userDigest = userDigest,
                    state = if (reviewRequired) ClaimState.REVIEW_REQUIRED else ClaimState.ALLOCATED,
                    reviewKind = if (reviewRequired) ReviewKind.ALLOCATION else null,
                    pendingFromState = if (reviewRequired) ClaimState.ELIGIBLE else null,
                    capacityReserved = !reviewRequired,
                    allocationPolicyVersion = campaign.policyVersion,
                    codeVerifier = issued.verifier.copyOf(),
                    generationKeyVersion = issued.generationKeyVersion,
                    verificationKeyVersion = issued.verificationKeyVersion,
                    expiresAt = now.plusSeconds(campaign.redemptionTtlSeconds),
                    redemptionReferenceDigest = null,
                    revision = 0,
                ),
            )
            val review = if (reviewRequired) openReview(claim) else null
            audits.append(claim.audit(if (reviewRequired) "ALLOCATION_REVIEW_REQUIRED" else "ALLOCATED"))
            log.debug {
                "voucher_allocation_completed campaignId=${campaign.campaignId} claimId=${claim.claimId} state=${claim.state}"
            }
            AllocationResult(
                claim = claim.toSnapshot(),
                allocationId = allocationId,
                oneTimeCode = issued.code.takeUnless { reviewRequired },
                reviewId = review?.id,
            )
        }
    }

    /**
     * openReview은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claim 업무 처리에 필요한 claim 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun openReview(claim: ClaimRecord): ReviewRecord {
        log.debug { "Insert review from claim. $claim" }

        return reviews.insert(
            ReviewRecord(
                id = 0,
                tenantId = claim.tenantId,
                campaignId = claim.campaignId,
                claimRowId = claim.id,
                claimId = claim.claimId,
                kind = ReviewKind.ALLOCATION,
                status = ReviewStatus.OPEN,
                reasonCode = "RISK_REVIEW",
                signalSummary = "risk=review",
                reviewerActorDigest = null,
                expectedClaimRevision = claim.revision,
                revision = 0,
            ),
        )
    }

    /**
     * validate은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun validate(command: AllocateVoucherCommand) {
        require(command.tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(command.userRef.length in 1..128) { "userRef must contain 1..128 characters" }
    }

}

/**
 * voucherUserDigest은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
 * @param userRef 업무 처리에 필요한 userRef 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun voucherUserDigest(
    tenantId: String,
    userRef: String,
): String = digestHex("voucher-user-v1", tenantId, userRef)

/**
 * requireCampaignActive은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
internal fun requireCampaignActive(
    campaign: CampaignRecord,
    now: Instant,
) {
    when (campaign.state) {
        CampaignState.PAUSED -> fail(VoucherCommandFailure.CAMPAIGN_PAUSED)
        CampaignState.ENDED -> fail(VoucherCommandFailure.CAMPAIGN_ENDED)
        CampaignState.DRAFT -> fail(VoucherCommandFailure.CAMPAIGN_NOT_ACTIVE)
        CampaignState.ACTIVE -> Unit
    }
    if (now.isBefore(campaign.startsAt)) fail(VoucherCommandFailure.CAMPAIGN_NOT_STARTED)
    if (!now.isBefore(campaign.endsAt)) fail(VoucherCommandFailure.CAMPAIGN_ENDED)
}

/**
 * requireAllocatable은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun requireAllocatable(
    campaign: CampaignRecord,
    now: Instant,
) = requireCampaignActive(campaign, now)

/**
 * toSnapshot은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun CampaignRecord.toSnapshot(): CampaignSnapshot =
    CampaignSnapshot(
        tenantId = tenantId,
        campaignId = campaignId,
        state = state,
        startsAt = startsAt,
        endsAt = endsAt,
        capacity = capacity,
        allocatedCount = allocatedCount,
        perUserLimit = perUserLimit,
        redemptionTtlSeconds = redemptionTtlSeconds,
        policyVersion = policyVersion,
        revision = revision,
    )

/**
 * toSnapshot은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun ClaimRecord.toSnapshot(): ClaimSnapshot =
    ClaimSnapshot(
        tenantId = tenantId,
        campaignId = campaignId,
        claimId = claimId,
        state = state,
        reviewKind = reviewKind,
        pendingFromState = pendingFromState,
        capacityReserved = capacityReserved,
        allocationPolicyVersion = allocationPolicyVersion,
        expiresAt = expiresAt,
        revision = revision,
    )

/**
 * audit은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param reasonCode 업무 처리에 필요한 reasonCode 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun ClaimRecord.audit(reasonCode: String): AuditRecord =
    AuditRecord(
        id = 0,
        tenantId = tenantId,
        campaignId = campaignId,
        aggregateType = "CLAIM",
        aggregateId = claimId,
        revision = revision,
        actorType = "USER",
        reasonCode = reasonCode,
        policyVersion = allocationPolicyVersion,
        correlationDigest = null,
    )

/**
 * digestHex은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param values 업무 처리에 필요한 values 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun digestHex(
    domain: String,
    vararg values: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest((sequenceOf(domain) + values.asSequence()).joinToString("\u0000").toUtf8Bytes())
        .joinToString("") { "%02x".format(it) }

/**
 * fail은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun fail(code: VoucherCommandFailure): Nothing =
    throw VoucherCommandException(code)
