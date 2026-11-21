package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeService
import io.bluetape4k.workshop.commerce.voucher.security.VoucherGenerationInput
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.*
/**
 * AcknowledgeVoucherCodeCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property claimId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class AcknowledgeVoucherCodeCommand(
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
 * AcknowledgeVoucherCodeResult은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property claim 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property code 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class AcknowledgeVoucherCodeResult(
    /**
     * `claim`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val claim: ClaimSnapshot,
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String,
)

/**
 * review-approved code를 한 번 전달하고, same-key response-loss recovery는 idempotency에 위임합니다.
 * @property transactions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property campaigns 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property claims 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audits 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property codes 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
@Service
internal class VoucherCodeAcknowledgementService(
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
) {
    companion object: KLogging() {
        /**
         * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private const val REVIEW_APPROVED_REASON = "REVIEW_APPROVED"
        /**
         * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private const val ACKNOWLEDGED_REASON = "CODE_ACKNOWLEDGED"
    }

    /**
     * `acknowledge`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = acknowledge(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun acknowledge(command: AcknowledgeVoucherCodeCommand): AcknowledgeVoucherCodeResult =
        transactions.foregroundTransaction {
            campaigns.findPublicForUpdate(command.tenantId, command.campaignId)
                ?: fail(VoucherCommandFailure.CAMPAIGN_NOT_FOUND)

            /**
             * `claim`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val claim = claims.findPublicForUpdate(command.tenantId, command.claimId)
                ?: fail(VoucherCommandFailure.CLAIM_NOT_FOUND)

            if (claim.campaignId != command.campaignId) {
                fail(VoucherCommandFailure.CLAIM_NOT_FOUND)
            }
            if (audits.hasReason(command.tenantId, claim.claimId, ACKNOWLEDGED_REASON)) {
                fail(VoucherCommandFailure.CODE_ALREADY_ACKNOWLEDGED)
            }
            if (claim.state != ClaimState.ALLOCATED ||
                !audits.hasReason(command.tenantId, claim.claimId, REVIEW_APPROVED_REASON)
            ) {
                fail(VoucherCommandFailure.CODE_ALREADY_ACKNOWLEDGED)
            }
            if (claim.revision != command.expectedRevision) {
                fail(VoucherCommandFailure.STALE_REVISION)
            }

            /**
             * `updated`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val updated = claim.copy(revision = claim.revision + 1)
            if (!claims.transition(updated, claim.revision)) {
                fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            }
            audits.append(updated.audit(ACKNOWLEDGED_REASON))
            /**
             * `generationVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
             *
             * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
             */
            val generationVersion = updated.generationKeyVersion
                ?: fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE)

            /**
             * `verificationVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
             *
             * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
             */
            val verificationVersion = updated.verificationKeyVersion
                ?: fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE)

            /**
             * `issued`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val issued = codes.reconstruct(
                input = VoucherGenerationInput(updated.tenantId, updated.campaignId, updated.allocationId),
                generationKeyVersion = generationVersion,
                verificationKeyVersion = verificationVersion,
            ) ?: fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE)

            if (!MessageDigest.isEqual(issued.verifier, updated.codeVerifier)) {
                fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE)
            }
            log.info { "voucher_code_acknowledged claimId=${updated.claimId} revision=${updated.revision}" }

            AcknowledgeVoucherCodeResult(updated.toSnapshot(), issued.code)
        }

}
