package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.voucher.admission.RiskSignalService
import io.bluetape4k.workshop.commerce.voucher.application.AcknowledgeVoucherCodeCommand
import io.bluetape4k.workshop.commerce.voucher.application.AllocateVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.AllocationService
import io.bluetape4k.workshop.commerce.voucher.application.ClaimCommandService
import io.bluetape4k.workshop.commerce.voucher.application.ClaimTransitionCommand
import io.bluetape4k.workshop.commerce.voucher.application.RedeemVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCodeAcknowledgementService
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.fixture.FixtureRiskOperation
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import io.bluetape4k.workshop.commerce.voucher.query.VoucherQueryService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.*
/**
 * AllocateClaimRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property userRef 호출 주체를 나타내는 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보나 비밀값은 저장·로그에 남기지 않습니다.
 */


internal data class AllocateClaimRequest(
    @field:NotBlank
    @field:Size(max = 64)
    /**
     * `userRef`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val userRef: String,
)
/**
 * AllocationHttpResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property claimId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property reviewId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property code 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class AllocationHttpResponse(
    /**
     * `claimId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val claimId: UUID,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant?,
    /**
     * `reviewId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val reviewId: Long?,
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String?,
)
/**
 * CampaignHttpResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property capacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property allocatedCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property remainingCapacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야
 * 합니다.
 * @property startsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property endsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 * @property observedAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class CampaignHttpResponse(
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `capacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacity: Int,
    /**
     * `allocatedCount`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val allocatedCount: Int,
    /**
     * `remainingCapacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val remainingCapacity: Int,
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
    /**
     * `observedAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val observedAt: Instant,
)
/**
 * ClaimHttpResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property claimId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class ClaimHttpResponse(
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
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant?,
)
/**
 * RedeemClaimRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property code 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property redemptionReference 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class RedeemClaimRequest(
    @field:NotBlank @field:Size(max = 64) val code: String,
    @field:jakarta.validation.constraints.Min(0) val expectedRevision: Long,
    @field:NotBlank @field:Size(max = 128) val redemptionReference: String,
)
/**
 * ClaimRevisionRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class ClaimRevisionRequest(
    @field:jakarta.validation.constraints.Min(0) val expectedRevision: Long,
)
/**
 * VoucherCodeHttpResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property claimId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property code 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class VoucherCodeHttpResponse(
    /**
     * `claimId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val claimId: UUID,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String,
)

/**
 * live customer API입니다. GET operation은 voucher code를 재구성하거나 노출하지 않습니다.
 * @property allocation 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property claims 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property acknowledgements 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property queries 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property executor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property risks 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property fixtureProvider 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@RestController
@RequestMapping("/api/v1")
internal class CustomerVoucherController(
    /**
     * `allocation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val allocation: AllocationService,
    /**
     * `claims`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    private val claims: ClaimCommandService,
    /**
     * `acknowledgements`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val acknowledgements: VoucherCodeAcknowledgementService,
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
     * `risks`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val risks: RiskSignalService,
    /**
     * `fixtureProvider`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val fixtureProvider: ObjectProvider<VoucherScenarioFixture>,
) {

    companion object: KLogging()

    /**
     * `allocate`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = allocate(campaignId = UUID.randomUUID(), tenantHeader = "tenant-a",
     * principalHeader =
     * null, idempotencyHeader = "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param principalHeader 요청 경계에서 검증된 `principalHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/campaigns/{campaignId}/claims")
    fun allocate(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: AllocateClaimRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        /**
         * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val principal = requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        if (body.userRef != principal) throw invalidRequest("userRef must match X-Workshop-Principal")

        /**
         * `executed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val executed = executor.execute(
            tenantId = tenant,
            principalRef = principal,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = "ALLOCATE",
            resourceId = campaignId,
            fingerprintMaterial = body.userRef,
        ) {
            /**
             * `riskSignal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val riskSignal = fixtureProvider.ifAvailable
                ?.signalFor(tenant, principal, FixtureRiskOperation.ALLOCATION)
                ?: risks.assess(tenant, principal)
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = allocation.allocate(
                AllocateVoucherCommand(tenant, campaignId, body.userRef, riskSignal),
            )
            /**
             * `descriptor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val descriptor = queries.descriptor(tenant, result.claim.claimId)
                .checkNotNull { "descriptor does not exist" }

            result.claim.storedAllocationResponse(
                kind = if (result.reviewId == null) {
                    VoucherResponseKind.ALLOCATION_ACCEPTED
                } else {
                    VoucherResponseKind.ALLOCATION_REVIEW_REQUIRED
                },
                status = if (result.reviewId == null) 201 else 202,
                allocationId = result.allocationId,
                reviewId = result.reviewId,
                generationKeyVersion = descriptor.generationKeyVersion,
                verificationKeyVersion = descriptor.verificationKeyVersion,
            )
        }

        return executedResponse(executed, request) {
            allocationResponse(tenant, executed)
        }
    }

    /**
     * `redeem`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = redeem(claimId = UUID.randomUUID(), tenantHeader = "tenant-a",
     * principalHeader =
     * null, idempotencyHeader = "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param claimId 요청 경계에서 검증된 `claimId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param principalHeader 요청 경계에서 검증된 `principalHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/claims/{claimId}/redeem")
    fun redeem(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: RedeemClaimRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        /**
         * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val principal = requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        requireOwnedClaim(tenant, principal, claimId)

        /**
         * `executed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val executed = executor.execute(
            tenantId = tenant,
            principalRef = principal,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = "REDEEM",
            resourceId = claimId,
            fingerprintMaterial = listOf(
                body.code,
                body.expectedRevision,
                body.redemptionReference
            ).joinToString("\u0000"),
        ) {
            /**
             * `riskSignal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val riskSignal = fixtureProvider.ifAvailable?.signalFor(tenant, principal, FixtureRiskOperation.REDEMPTION)
                ?: risks.assess(tenant, principal)
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = claims.redeem(
                RedeemVoucherCommand(
                    tenantId = tenant,
                    code = body.code,
                    expectedRevision = body.expectedRevision,
                    redemptionReference = body.redemptionReference,
                    riskSignal = riskSignal,
                    claimId = claimId,
                ),
            )
            result.storedClaimResponse(
                kind = if (result.state == ClaimState.REVIEW_REQUIRED) {
                    VoucherResponseKind.REDEMPTION_REVIEW_REQUIRED
                } else {
                    VoucherResponseKind.REDEMPTION_ACCEPTED
                },
                status = if (result.state == ClaimState.REVIEW_REQUIRED) 202 else 200,
            )
        }

        return executedResponse(
            executed = executed,
            request = request,
            successBody = executed.response::claimBody
        )
    }

    /**
     * `release`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = release(claimId = UUID.randomUUID(), tenantHeader = "tenant-a",
     * principalHeader =
     * null, idempotencyHeader = "demo-idempotency-1", body = body, request = httpRequest)
     * ```
     * @param claimId 요청 경계에서 검증된 `claimId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param principalHeader 요청 경계에서 검증된 `principalHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/claims/{claimId}/release")
    fun release(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ClaimRevisionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        /**
         * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val principal = requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        /**
         * `owned`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val owned = requireOwnedClaim(tenant, principal, claimId)

        /**
         * `executed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val executed = executor.execute(
            tenantId = tenant,
            principalRef = principal,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = "RELEASE",
            resourceId = claimId,
            fingerprintMaterial = body.expectedRevision.toString(),
        ) {
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = claims.release(
                ClaimTransitionCommand(
                    tenantId = tenant,
                    campaignId = owned.campaignId,
                    claimId = claimId,
                    expectedRevision = body.expectedRevision
                )
            )
            result.storedClaimResponse(VoucherResponseKind.CLAIM_RELEASED)
        }
        return executedResponse(
            executed = executed,
            request = request,
            successBody = executed.response::claimBody
        )
    }

    /**
     * `acknowledgeCode`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = acknowledgeCode(claimId = UUID.randomUUID(), tenantHeader = "tenant-a",
     * principalHeader = null, idempotencyHeader = "demo-idempotency-1", body = body, request =
     * httpRequest)
     * ```
     * @param claimId 요청 경계에서 검증된 `claimId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param principalHeader 요청 경계에서 검증된 `principalHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/claims/{claimId}/code-acknowledgements")
    fun acknowledgeCode(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: ClaimRevisionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        /**
         * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val principal = requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        /**
         * `owned`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val owned = requireOwnedClaim(tenant, principal, claimId)
        /**
         * `executed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val executed = executor.execute(
            tenantId = tenant,
            principalRef = principal,
            rawIdempotencyKey = idempotencyHeader ?: throw invalidRequest("Idempotency-Key is required"),
            operation = "CODE_ACKNOWLEDGEMENT",
            resourceId = claimId,
            fingerprintMaterial = body.expectedRevision.toString(),
        ) {
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = acknowledgements.acknowledge(
                AcknowledgeVoucherCodeCommand(tenant, owned.campaignId, claimId, body.expectedRevision),
            )
            /**
             * `descriptor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val descriptor = queries.descriptor(tenant, claimId).checkNotNull { "descriptor does not exist" }
            result.claim.storedClaimResponse(
                kind = VoucherResponseKind.CODE_ACKNOWLEDGED,
                allocationId = descriptor.allocationId,
                generationKeyVersion = descriptor.generationKeyVersion,
                verificationKeyVersion = descriptor.verificationKeyVersion,
            )
        }
        return executedResponse(executed, request) {
            /**
             * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
             *
             * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
             */
            val code = queries.activeCode(tenant, claimId)
                ?: throw VoucherApiException(
                    "IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE",
                    503,
                    "voucher replay material is unavailable"
                )
            /**
             * `snapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val snapshot = executed.response.claimBody()
            VoucherCodeHttpResponse(snapshot.claimId, snapshot.state, snapshot.revision, code)
        }
    }

    /**
     * `campaign`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = campaign(campaignId = UUID.randomUUID(), tenantHeader = "tenant-a",
     * principalHeader =
     * null)
     * ```
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param principalHeader 요청 경계에서 검증된 `principalHeader`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/campaigns/{campaignId}")
    fun campaign(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
    ): CampaignHttpResponse {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)

        /**
         * `snapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val snapshot = queries.campaign(tenant, campaignId)
            ?: throw VoucherApiException("CAMPAIGN_NOT_FOUND", 404, "campaign was not found")

        return snapshot.toHttp()
    }

    /**
     * `claim`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = claim(claimId = UUID.randomUUID(), tenantHeader = "tenant-a", principalHeader
     * = null)
     * ```
     * @param claimId 요청 경계에서 검증된 `claimId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param principalHeader 요청 경계에서 검증된 `principalHeader`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/claims/{claimId}")
    fun claim(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
    ): ClaimHttpResponse {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        /**
         * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val principal = requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)

        /**
         * `snapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val snapshot = queries.claimOwned(tenant, principal, claimId)
            ?: throw VoucherApiException("CLAIM_NOT_FOUND", 404, "claim was not found")

        return snapshot.toHttp()
    }

    /**
     * allocationResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param executed 업무 처리에 필요한 executed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun allocationResponse(
        tenantId: String,
        executed: ExecutedHttpCommand,
    ): AllocationHttpResponse {
        val code = if (executed.response.responseKind == VoucherResponseKind.ALLOCATION_ACCEPTED) {
            queries.activeCode(tenantId, executed.response.aggregateId)
                ?: throw VoucherApiException(
                    "IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE",
                    503,
                    "voucher replay material is unavailable",
                )
        } else {
            null
        }
        log.info {
            "voucher_allocation_http_completed claimId=${executed.response.aggregateId} " +
                    "kind=${executed.response.responseKind} replayed=${executed.replayed}"
        }
        return executed.response.allocationBody(code)
    }

    /**
     * requireOwnedClaim은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principalRef 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param claimId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun requireOwnedClaim(
        tenantId: String,
        principalRef: String,
        claimId: UUID,
    ): ClaimSnapshot =
        queries.claimOwned(tenantId, principalRef, claimId)
            ?: throw VoucherApiException("CLAIM_NOT_FOUND", 404, "claim was not found")
}

/**
 * toHttp은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun CampaignSnapshot.toHttp(): CampaignHttpResponse =
    CampaignHttpResponse(
        campaignId = campaignId,
        state = state.name,
        revision = revision,
        policyVersion = policyVersion,
        capacity = capacity,
        allocatedCount = allocatedCount,
        remainingCapacity = (capacity - allocatedCount).coerceAtLeast(0),
        startsAt = startsAt,
        endsAt = endsAt,
        observedAt = Instant.now(),
    )

/**
 * toHttp은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun ClaimSnapshot.toHttp(): ClaimHttpResponse =
    ClaimHttpResponse(
        campaignId = campaignId,
        claimId = claimId,
        state = state.name,
        revision = revision,
        policyVersion = allocationPolicyVersion,
        expiresAt = expiresAt
    )
