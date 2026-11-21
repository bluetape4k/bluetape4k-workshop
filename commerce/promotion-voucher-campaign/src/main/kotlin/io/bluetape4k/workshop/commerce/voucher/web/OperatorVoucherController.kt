package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.application.CampaignCommandService
import io.bluetape4k.workshop.commerce.voucher.application.CampaignPolicyCommand
import io.bluetape4k.workshop.commerce.voucher.application.CampaignTransitionCommand
import io.bluetape4k.workshop.commerce.voucher.application.ClaimCommandService
import io.bluetape4k.workshop.commerce.voucher.application.ClaimTransitionCommand
import io.bluetape4k.workshop.commerce.voucher.application.CreateCampaignCommand
import io.bluetape4k.workshop.commerce.voucher.application.RetryableVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.ReviewCommandService
import io.bluetape4k.workshop.commerce.voucher.application.ReviewDecisionCommand
import io.bluetape4k.workshop.commerce.voucher.application.digestHex
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewStatus
import io.bluetape4k.workshop.commerce.voucher.query.VoucherQueryService
import io.bluetape4k.workshop.commerce.voucher.reconciliation.ReconciliationResult
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationWorker
import io.bluetape4k.workshop.commerce.voucher.reconciliation.WorkerRunResult
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.*
/**
 * CreateCampaignRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property startsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property endsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 * @property capacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property perUserLimit 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property redemptionTtlSeconds 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


internal data class CreateCampaignRequest(
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `startsAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val startsAt: Instant,
    /**
     * `endsAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val endsAt: Instant,
    @field:Min(1) @field:Max(1_000_000) val capacity: Int,
    @field:Min(1) @field:Max(1_000) val perUserLimit: Int,
    @field:Positive val redemptionTtlSeconds: Long,
)
/**
 * ExpectedRevisionRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class ExpectedRevisionRequest(
    @field:Min(0) val expectedRevision: Long,
)
/**
 * CampaignPolicyRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property capacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property perUserLimit 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property redemptionTtlSeconds 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


internal data class CampaignPolicyRequest(
    @field:Min(0) val expectedRevision: Long,
    @field:Min(1) @field:Max(1_000_000) val capacity: Int,
    @field:Min(1) @field:Max(1_000) val perUserLimit: Int,
    @field:Positive val redemptionTtlSeconds: Long,
)
/**
 * ReviewDecisionHttpRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property claimId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedReviewRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property expectedClaimRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class ReviewDecisionHttpRequest(
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
    @field:Min(0) val expectedReviewRevision: Long,
    @field:Min(0) val expectedClaimRevision: Long,
)
/**
 * RevokeClaimRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class RevokeClaimRequest(
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    @field:Min(0) val expectedRevision: Long,
)
/**
 * ReconciliationHttpResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property processed 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property skipped 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property failed 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property cursor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property deadlineReached 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */


internal data class ReconciliationHttpResponse(
    /**
     * `processed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val processed: Int,
    /**
     * `skipped`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val skipped: Int,
    /**
     * `failed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val failed: Int,
    /**
     * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cursor: String?,
    /**
     * `deadlineReached`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val deadlineReached: Boolean,
)

/**
 * OperatorPage은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class OperatorPage<T>(val items: List<T>, val nextCursor: String?)
/**
 * ReviewHttpItem은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property claimId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property kind 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property status 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property reasonCode 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property expectedClaimRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property createdAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class ReviewHttpItem(
    /**
     * `id`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
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
     * `kind`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val kind: String,
    /**
     * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: String,
    /**
     * `reasonCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reasonCode: String,
    /**
     * `expectedClaimRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedClaimRevision: Long,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `createdAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val createdAt: Instant?,
)
/**
 * ReconciliationBacklogHttpItem은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property eventId 이벤트의 불변 식별자입니다. inbox/outbox 중복 제거와 감사 추적의 기준이므로 재시도에도 같은 값을 유지합니다.
 * @property aggregateType 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property aggregateId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property observedSequence 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property status 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property attempt 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께 적용합니다.
 * @property nextAttemptAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 * @property createdAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class ReconciliationBacklogHttpItem(
    /**
     * `id`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
    /**
     * `eventId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
    /**
     * `aggregateType`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val aggregateType: String,
    /**
     * `aggregateId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId: UUID,
    /**
     * `observedSequence`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val observedSequence: Long,
    /**
     * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: String,
    /**
     * `attempt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val attempt: Int,
    /**
     * `nextAttemptAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val nextAttemptAt: Instant,
    /**
     * `createdAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val createdAt: Instant?,
)

/**
 * [OperatorAccessFilter]와 route-specific precondition 뒤에 있는 live operator API입니다.
 * @property campaigns 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property claims 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reviews 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property queries 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property executor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reconciliation 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@RestController
@RequestMapping("/operator/api/v1")
internal class OperatorVoucherController(
    /**
     * `campaigns`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val campaigns: CampaignCommandService,
    /**
     * `claims`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    private val claims: ClaimCommandService,
    /**
     * `reviews`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val reviews: ReviewCommandService,
    /**
     * `queries`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val queries: VoucherQueryService,
    /**
     * `executor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: VoucherHttpCommandExecutor,
    /**
     * `reconciliation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val reconciliation: VoucherReconciliationWorker,
) {

    companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val OPERATOR_PRINCIPAL = "workshop-operator"

        /**
         * encodeCursor은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무
         * 오류로 분류합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param id 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
         */
        private fun encodeCursor(id: Long): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString("v1:$id".toUtf8Bytes())

        /**
         * decodeCursor은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무
         * 오류로 분류합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        private fun decodeCursor(cursor: String?): Long? {
            if (cursor == null) return null
            val decoded = runCatching {
                String(Base64.getUrlDecoder().decode(cursor), UTF_8)
            }.getOrElse {
                throw invalidRequest("cursor is invalid")
            }

            return decoded.removePrefix("v1:").takeIf { decoded.startsWith("v1:") }
                ?.toLongOrNull()
                ?: throw invalidRequest("cursor is invalid")
        }
    }

    /**
     * `listReviews`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = listReviews(tenantHeader = "tenant-a", status = status, cursor = null, limit
     * = 25)
     * ```
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param status 요청 경계에서 검증된 `status`입니다.
     * @param cursor 요청 경계에서 검증된 `cursor`입니다.
     * @param limit 요청 경계에서 검증된 `limit`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/reviews")
    fun listReviews(
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestParam(defaultValue = "OPEN") status: ReviewStatus,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "100") @Min(1) @Max(100) limit: Int,
    ): OperatorPage<ReviewHttpItem> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        /**
         * `rows`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
         *
         * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
         */
        val rows = queries.reviews(tenant, status, decodeCursor(cursor), limit + 1)
        /**
         * `page`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val page = rows.take(limit)

        return OperatorPage(
            page.map {
                ReviewHttpItem(
                    it.id, it.campaignId, it.claimId, it.kind.name, it.status.name, it.reasonCode,
                    it.expectedClaimRevision, it.revision, it.createdAt,
                )
            },
            rows.getOrNull(limit - 1)?.id?.takeIf { rows.size > limit }?.let(::encodeCursor),
        )
    }

    /**
     * `reconciliationBacklog`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = reconciliationBacklog(tenantHeader = "tenant-a", cursor = null, limit = 25)
     * ```
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param cursor 요청 경계에서 검증된 `cursor`입니다.
     * @param limit 요청 경계에서 검증된 `limit`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/reconciliation/backlog")
    fun reconciliationBacklog(
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "100") @Min(1) @Max(100) limit: Int,
    ): OperatorPage<ReconciliationBacklogHttpItem> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        /**
         * `rows`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
         *
         * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
         */
        val rows = queries.reconciliationBacklog(tenant, decodeCursor(cursor), limit + 1)
        /**
         * `page`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val page = rows.take(limit)

        return OperatorPage(
            page.map {
                ReconciliationBacklogHttpItem(
                    it.id, it.eventId, it.aggregateType, it.aggregateId, it.observedSequence,
                    it.status.name, it.attempt, it.nextAttemptAt, it.createdAt,
                )
            },
            rows.getOrNull(limit - 1)?.id?.takeIf { rows.size > limit }?.let(::encodeCursor),
        )
    }

    /**
     * `createCampaign`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = createCampaign(tenantHeader = "tenant-a", idempotencyHeader =
     * "demo-idempotency-1",
     * ifNoneMatch = "demo", body = body, request = httpRequest)
     * ```
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param ifNoneMatch 요청 경계에서 검증된 `ifNoneMatch`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/campaigns")
    fun createCampaign(
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @RequestHeader("If-None-Match", required = false) ifNoneMatch: String?,
        @Valid @RequestBody body: CreateCampaignRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        if (ifNoneMatch != "*") throw invalidRequest("If-None-Match must be *")

        /**
         * `executed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val executed = executor.execute(
            tenantId = tenant,
            principalRef = OPERATOR_PRINCIPAL,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = "CAMPAIGN_CREATE",
            resourceId = body.campaignId,
            fingerprintMaterial = listOf(
                body.campaignId,
                body.startsAt,
                body.endsAt,
                body.capacity,
                body.perUserLimit,
                body.redemptionTtlSeconds
            )
                .joinToString("\u0000"),
        ) {
            /**
             * `created`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val created = campaigns.create(
                CreateCampaignCommand(
                    tenant,
                    body.campaignId,
                    body.startsAt,
                    body.endsAt,
                    body.capacity,
                    body.perUserLimit,
                    body.redemptionTtlSeconds,
                ),
            )
            created.storedCampaignResponse(
                kind = VoucherResponseKind.CAMPAIGN_CREATED,
                status = 201,
                publicHeaders = mapOf("Location" to "/api/v1/campaigns/${created.campaignId}"),
            )
        }

        return executedResponse(
            executed = executed,
            request = request,
            successBody = executed.response::campaignBody
        )
    }

    /**
     * `activateCampaign`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = activateCampaign(campaignId = UUID.randomUUID(), tenantHeader = "tenant-a",
     * idempotencyHeader = "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/campaigns/{campaignId}/activate")
    fun activateCampaign(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ExpectedRevisionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        return campaignTransition(
            campaignId = campaignId,
            tenantHeader = tenantHeader,
            idempotencyHeader = idempotencyHeader,
            expectedRevision = body.expectedRevision,
            operation = "CAMPAIGN_ACTIVATE",
            responseKind = VoucherResponseKind.CAMPAIGN_ACTIVATED,
            request = request,
            transition = campaigns::activate,
        )
    }

    /**
     * `pauseCampaign`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = pauseCampaign(campaignId = UUID.randomUUID(), tenantHeader = "tenant-a",
     * idempotencyHeader = "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/campaigns/{campaignId}/pause")
    fun pauseCampaign(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ExpectedRevisionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> =
        campaignTransition(
            campaignId = campaignId,
            tenantHeader = tenantHeader,
            idempotencyHeader = idempotencyHeader,
            expectedRevision = body.expectedRevision,
            operation = "CAMPAIGN_PAUSE",
            responseKind = VoucherResponseKind.CAMPAIGN_PAUSE_ACCEPTED,
            request = request,
            transition = campaigns::pause,
        )

    /**
     * `endCampaign`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = endCampaign(campaignId = UUID.randomUUID(), tenantHeader = "tenant-a",
     * idempotencyHeader = "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/campaigns/{campaignId}/end")
    fun endCampaign(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ExpectedRevisionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> =
        campaignTransition(
            campaignId = campaignId,
            tenantHeader = tenantHeader,
            idempotencyHeader = idempotencyHeader,
            expectedRevision = body.expectedRevision,
            operation = "CAMPAIGN_END",
            responseKind = VoucherResponseKind.CAMPAIGN_END_ACCEPTED,
            request = request,
            transition = campaigns::end,
        )

    /**
     * `updatePolicy`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = updatePolicy(campaignId = UUID.randomUUID(), tenantHeader = "tenant-a",
     * idempotencyHeader = "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/campaigns/{campaignId}/policy")
    fun updatePolicy(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: CampaignPolicyRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)

        /**
         * `executed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val executed = executor.execute(
            tenantId = tenant,
            principalRef = OPERATOR_PRINCIPAL,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = "CAMPAIGN_POLICY",
            resourceId = campaignId,
            fingerprintMaterial = listOf(
                body.expectedRevision,
                body.capacity,
                body.perUserLimit,
                body.redemptionTtlSeconds
            )
                .joinToString("\u0000"),
        ) {
            /**
             * `updated`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val updated = campaigns.updatePolicy(
                CampaignPolicyCommand(
                    tenantId = tenant,
                    campaignId = campaignId,
                    expectedRevision = body.expectedRevision,
                    capacity = body.capacity,
                    perUserLimit = body.perUserLimit,
                    redemptionTtlSeconds = body.redemptionTtlSeconds,
                ),
            )
            updated.storedCampaignResponse(
                kind = VoucherResponseKind.CAMPAIGN_POLICY_UPDATED,
                status = 200,
                publicHeaders = mapOf("ETag" to "\"${updated.revision}\""),
            )
        }
        return executedResponse(
            executed = executed,
            request = request,
            successBody = executed.response::campaignBody
        )
    }

    /**
     * `approveReview`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = approveReview(reviewId = 1L, tenantHeader = "tenant-a", idempotencyHeader =
     * "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param reviewId 요청 경계에서 검증된 `reviewId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/reviews/{reviewId}/approve")
    fun approveReview(
        @PathVariable reviewId: Long,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ReviewDecisionHttpRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> =
        reviewDecision(
            reviewId = reviewId,
            tenantHeader = tenantHeader,
            idempotencyHeader = idempotencyHeader,
            body = body,
            approved = true,
            request = request
        )

    /**
     * `rejectReview`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = rejectReview(reviewId = 1L, tenantHeader = "tenant-a", idempotencyHeader =
     * "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param reviewId 요청 경계에서 검증된 `reviewId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/reviews/{reviewId}/reject")
    fun rejectReview(
        @PathVariable reviewId: Long,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ReviewDecisionHttpRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> =
        reviewDecision(
            reviewId = reviewId,
            tenantHeader = tenantHeader,
            idempotencyHeader = idempotencyHeader,
            body = body,
            approved = false,
            request = request
        )

    /**
     * `revokeClaim`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = revokeClaim(claimId = UUID.randomUUID(), tenantHeader = "tenant-a",
     * idempotencyHeader
     * = "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param claimId 요청 경계에서 검증된 `claimId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/claims/{claimId}/revoke")
    fun revokeClaim(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: RevokeClaimRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)

        /**
         * `executed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val executed = executor.execute(
            tenantId = tenant,
            principalRef = OPERATOR_PRINCIPAL,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = "CLAIM_REVOKE",
            resourceId = claimId,
            fingerprintMaterial = "${body.campaignId}\u0000${body.expectedRevision}",
        ) {
            /**
             * `updated`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val updated = claims.revoke(
                ClaimTransitionCommand(
                    tenantId = tenant,
                    campaignId = body.campaignId,
                    claimId = claimId,
                    expectedRevision = body.expectedRevision
                )
            )
            updated.storedClaimResponse(VoucherResponseKind.CLAIM_REVOCATION_ACCEPTED)
        }
        return executedResponse(
            executed = executed,
            request = request,
            successBody = executed.response::claimBody
        )
    }

    /**
     * `runReconciliation`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = runReconciliation(tenantHeader = "tenant-a", idempotencyHeader =
     * "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/reconciliation/run")
    fun runReconciliation(
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: EmptyOperatorRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        /**
         * `resourceId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val resourceId = UUID.nameUUIDFromBytes("$tenant\u0000reconciliation".toUtf8Bytes())

        /**
         * `executed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val executed = executor.executeExternal(
            tenantId = tenant,
            principalRef = OPERATOR_PRINCIPAL,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = "RECONCILIATION_RUN",
            resourceId = resourceId,
            fingerprintMaterial = "manual",
        ) {
            when (val result = reconciliation.runManual()) {
                is WorkerRunResult.Manual          -> result.result.storedReconciliation(resourceId)
                WorkerRunResult.LocalRunInProgress ->
                    throw RetryableVoucherCommand(
                        StoredHttpResponse(
                            responseKind = VoucherResponseKind.RECONCILIATION_IN_PROGRESS,
                            status = 409,
                            headers = mapOf("Retry-After" to "1"),
                            aggregateId = resourceId,
                            allocationId = null,
                            aggregateRevision = 0,
                            generationKeyVersion = null,
                            verificationKeyVersion = null,
                        ),
                    )
                else                               ->
                    error("manual reconciliation returned an unsupported worker result")
            }
        }
        return executedResponse(executed, request) {
            executed.response.reconciliationBody()
        }
    }

    /**
     * campaignTransition은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param tenantHeader 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param idempotencyHeader 업무 처리에 필요한 idempotencyHeader 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param responseKind 업무 처리에 필요한 responseKind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param transition 업무 처리에 필요한 transition 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun campaignTransition(
        campaignId: UUID,
        tenantHeader: String?,
        idempotencyHeader: String?,
        expectedRevision: Long,
        operation: String,
        responseKind: VoucherResponseKind,
        request: HttpServletRequest,
        transition: (CampaignTransitionCommand) -> CampaignSnapshot,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)

        val executed = executor.execute(
            tenantId = tenant,
            principalRef = OPERATOR_PRINCIPAL,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = operation,
            resourceId = campaignId,
            fingerprintMaterial = expectedRevision.toString(),
        ) {
            val updated = transition(CampaignTransitionCommand(tenant, campaignId, expectedRevision))
            updated.storedCampaignResponse(
                kind = responseKind,
                status = 200,
                publicHeaders = mapOf("ETag" to "\"${updated.revision}\""),
            )
        }
        return executedResponse(
            executed = executed,
            request = request,
            successBody = executed.response::campaignBody
        )
    }

    /**
     * reviewDecision은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param reviewId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param tenantHeader 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param idempotencyHeader 업무 처리에 필요한 idempotencyHeader 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param body 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param approved 업무 처리에 필요한 approved 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun reviewDecision(
        reviewId: Long,
        tenantHeader: String?,
        idempotencyHeader: String?,
        body: ReviewDecisionHttpRequest,
        approved: Boolean,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        val operation = if (approved) "REVIEW_APPROVE" else "REVIEW_REJECT"
        val executed = executor.execute(
            tenantId = tenant,
            principalRef = OPERATOR_PRINCIPAL,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = operation,
            resourceId = body.claimId,
            fingerprintMaterial = listOf(
                reviewId,
                body.campaignId,
                body.expectedReviewRevision,
                body.expectedClaimRevision
            )
                .joinToString("\u0000"),
        ) {
            val command = ReviewDecisionCommand(
                tenantId = tenant,
                campaignId = body.campaignId,
                claimId = body.claimId,
                reviewId = reviewId,
                expectedReviewRevision = body.expectedReviewRevision,
                expectedClaimRevision = body.expectedClaimRevision,
                reviewerActorDigest = digestHex("voucher-operator-actor-v1", tenant, OPERATOR_PRINCIPAL),
            )
            val result = if (approved) reviews.approve(command) else reviews.reject(command)

            result.claim.storedClaimResponse(
                if (approved) VoucherResponseKind.REVIEW_APPROVED
                else VoucherResponseKind.REVIEW_REJECTED,
            )
        }

        return executedResponse(
            executed = executed,
            request = request,
            successBody = executed.response::claimBody
        )
    }
}

/**
 * EmptyOperatorRequest은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class EmptyOperatorRequest

/**
 * storedReconciliation은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param resourceId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
private fun ReconciliationResult.storedReconciliation(resourceId: UUID): StoredHttpResponse =
    StoredHttpResponse(
        responseKind = VoucherResponseKind.RECONCILIATION_COMPLETED,
        status = 200,
        headers = mapOf(RECONCILIATION_DESCRIPTOR_HEADER to encodeReconciliation()),
        aggregateId = resourceId,
        allocationId = null,
        aggregateRevision = 0,
        generationKeyVersion = null,
        verificationKeyVersion = null,
    )

/**
 * encodeReconciliation은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무
 * 오류로 분류합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ReconciliationResult.encodeReconciliation(): String =
    listOf(processed, skipped, failed, lastCursor ?: "-", if (deadlineReached) 1 else 0)
        .joinToString(":")

/**
 * reconciliationBody은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun StoredHttpResponse.reconciliationBody(): ReconciliationHttpResponse {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val values = headers[RECONCILIATION_DESCRIPTOR_HEADER]
        .requireNotNull("headers[$RECONCILIATION_DESCRIPTOR_HEADER]")
        .split(':')
    require(values.size == 5) { "invalid stored reconciliation descriptor" }

    return ReconciliationHttpResponse(
        processed = values[0].toInt(),
        skipped = values[1].toInt(),
        failed = values[2].toInt(),
        cursor = values[3].takeUnless { it == "-" },
        deadlineReached = values[4] == "1",
    )
}

@RestController
/**
 * VoucherRuntimeController은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class VoucherRuntimeController {
    /**
     * `runtime`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = runtime()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/internal/runtime-thread")
    fun runtime(): Map<String, Any> = mapOf(
        "virtual" to Thread.currentThread().isVirtual,
        "javaFeature" to Runtime.version().feature(),
    )
}
