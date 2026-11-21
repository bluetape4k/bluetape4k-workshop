package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.AllocateVoucherCommandInput
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedVoucherCommands
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedVoucherLifecycleCommands
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.RedeemVoucherCommandInput
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.ReleaseVoucherCommandInput
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.VoucherCommandExecution
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.*

/**
 * `FINGERPRINT_CONFLICT_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val FINGERPRINT_CONFLICT_STATUS = 409
/**
 * `VOUCHER_PUBLIC_REVISION_OFFSET`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
private const val VOUCHER_PUBLIC_REVISION_OFFSET = 2
/**
 * AllocateVoucherHttpRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property userRef 호출 주체를 나타내는 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보나 비밀값은 저장·로그에 남기지 않습니다.
 */


internal data class AllocateVoucherHttpRequest(
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
 * VoucherAllocationHttpResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property claimId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property reviewId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property code 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class VoucherAllocationHttpResponse(
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
 * RedeemVoucherHttpRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property code 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property redemptionReference 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class RedeemVoucherHttpRequest(
    @field:NotBlank
    @field:Size(max = 64)
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: String,
    @field:Min(0)
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    @field:NotBlank
    @field:Size(max = 128)
    /**
     * `redemptionReference`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val redemptionReference: String,
)
/**
 * VoucherRevisionHttpRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class VoucherRevisionHttpRequest(
    @field:Min(0)
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
)
/**
 * VoucherClaimHttpResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property claimId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class VoucherClaimHttpResponse(
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
 * VoucherCommandHttpService은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property commands 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property lifecycle 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property snapshots 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */


internal class VoucherCommandHttpService(
    /**
     * `commands`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commands: EventSourcedVoucherCommands,
    /**
     * `lifecycle`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val lifecycle: EventSourcedVoucherLifecycleCommands,
    /**
     * `snapshots`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val snapshots: CampaignProjectionSnapshotReader,
) {

    private companion object: KLogging() {
        /**
         * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        val TRANSITION_SUCCESS_OUTCOMES = setOf(
            ReceiptOutcome.VOUCHER_REDEEMED,
            ReceiptOutcome.VOUCHER_RELEASED
        )
    }

    /**
     * allocate은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param body 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun allocate(
        tenant: String,
        principal: String,
        idempotencyKey: String,
        campaignId: UUID,
        body: AllocateVoucherHttpRequest,
    ): VoucherAllocationHttpResult =
        commands
            .allocate(
                input = AllocateVoucherCommandInput(
                    tenant = tenant,
                    principal = principal,
                    idempotencyKey = idempotencyKey,
                    campaignId = campaignId,
                    userRef = body.userRef,
                ),
            ).toHttp(tenant, campaignId)

    /**
     * redeem은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param voucherId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param body 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun redeem(
        tenant: String,
        principal: String,
        idempotencyKey: String,
        voucherId: UUID,
        body: RedeemVoucherHttpRequest,
    ): VoucherTransitionHttpResult =
        lifecycle
            .redeem(
                input = RedeemVoucherCommandInput(
                    tenant = tenant,
                    principal = principal,
                    idempotencyKey = idempotencyKey,
                    voucherId = voucherId,
                    code = body.code,
                    expectedRevision = body.expectedRevision,
                    redemptionReference = body.redemptionReference,
                ),
            ).toTransitionHttp(tenant)

    /**
     * release은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param voucherId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param body 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun release(
        tenant: String,
        principal: String,
        idempotencyKey: String,
        voucherId: UUID,
        body: VoucherRevisionHttpRequest,
    ): VoucherTransitionHttpResult =
        lifecycle
            .release(
                input = ReleaseVoucherCommandInput(
                    tenant = tenant,
                    principal = principal,
                    idempotencyKey = idempotencyKey,
                    voucherId = voucherId,
                    expectedRevision = body.expectedRevision,
                ),
            ).toTransitionHttp(tenant)

    /**
     * toHttp은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun VoucherCommandExecution.toHttp(
        tenant: String,
        campaignId: UUID,
    ): VoucherAllocationHttpResult = when (this) {
        is VoucherCommandExecution.Completed        ->
            if (descriptor.outcome == ReceiptOutcome.VOUCHER_ALLOCATED) {
                val allocationId = checkNotNull(descriptor.allocationId)
                val voucher = checkNotNull(commands.voucher(tenant, allocationId))
                val streamPosition = checkNotNull(descriptor.streamPosition)
                val projectionPosition = snapshots
                    .read(TenantId(tenant), campaignId)
                    .positions.projectionPosition
                    .coerceAtMost(streamPosition)

                VoucherAllocationHttpResult.Allocated(
                    body = VoucherAllocationHttpResponse(
                        claimId = allocationId,
                        state = voucher.state.name,
                        revision = voucher.version - VOUCHER_PUBLIC_REVISION_OFFSET,
                        policyVersion = voucher.policyVersion - 1,
                        expiresAt = voucher.expiresAt,
                        reviewId = null,
                        code = commands.voucherCode(
                            tenant = tenant,
                            campaignId = campaignId,
                            allocationId = allocationId,
                            keyVersion = checkNotNull(descriptor.generationKeyVersion),
                        ),
                    ),
                    replayed = replayed,
                    positions = ProjectionPositions(streamPosition, projectionPosition),
                )
            } else {
                VoucherAllocationHttpResult.Rejected(descriptor.status, descriptor.outcome.toFailureCode())
            }

        VoucherCommandExecution.FingerprintConflict ->
            VoucherAllocationHttpResult.Rejected(FINGERPRINT_CONFLICT_STATUS, "IDEMPOTENCY_FINGERPRINT_CONFLICT")

        VoucherCommandExecution.InProgress          -> VoucherAllocationHttpResult.InProgress
        VoucherCommandExecution.KeyUnavailable      -> VoucherAllocationHttpResult.KeyUnavailable
    }

    /**
     * toTransitionHttp은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     */
    private fun VoucherCommandExecution.toTransitionHttp(tenant: String): VoucherTransitionHttpResult = when (this) {
        is VoucherCommandExecution.Completed        ->
            if (descriptor.outcome in TRANSITION_SUCCESS_OUTCOMES) {
                val voucherId = checkNotNull(descriptor.allocationId)
                val voucher = checkNotNull(commands.voucher(tenant, voucherId))
                val streamPosition = checkNotNull(descriptor.streamPosition)
                val campaignId = checkNotNull(voucher.campaignId)
                val projectionPosition = snapshots
                    .read(TenantId(tenant), campaignId)
                    .positions.projectionPosition
                    .coerceAtMost(streamPosition)

                VoucherTransitionHttpResult.Transitioned(
                    body = VoucherClaimHttpResponse(
                        campaignId = campaignId,
                        claimId = voucherId,
                        state = voucher.state.name,
                        revision = voucher.version - VOUCHER_PUBLIC_REVISION_OFFSET,
                        policyVersion = voucher.policyVersion - 1,
                        expiresAt = voucher.expiresAt,
                    ),
                    replayed = replayed,
                    positions = ProjectionPositions(streamPosition, projectionPosition),
                )
            } else {
                VoucherTransitionHttpResult.Rejected(descriptor.status, descriptor.outcome.toFailureCode())
            }

        VoucherCommandExecution.FingerprintConflict ->
            VoucherTransitionHttpResult.Rejected(FINGERPRINT_CONFLICT_STATUS, "IDEMPOTENCY_FINGERPRINT_CONFLICT")

        VoucherCommandExecution.InProgress          -> VoucherTransitionHttpResult.InProgress
        VoucherCommandExecution.KeyUnavailable      -> VoucherTransitionHttpResult.KeyUnavailable
    }
}

/**
 * VoucherAllocationHttpResult은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface VoucherAllocationHttpResult {
/**
 * Allocated은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property body 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 * @property positions 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */

    data class Allocated(
        /**
         * `body`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val body: VoucherAllocationHttpResponse,
        /**
         * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val replayed: Boolean,
        /**
         * `positions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val positions: ProjectionPositions,
    ): VoucherAllocationHttpResult
/**
 * Rejected은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property status 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property code 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    data class Rejected(
        /**
         * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        val status: Int,
        /**
         * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val code: String,
    ): VoucherAllocationHttpResult

    /**
     * InProgress은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object InProgress: VoucherAllocationHttpResult

    /**
     * KeyUnavailable은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object KeyUnavailable: VoucherAllocationHttpResult
}

/**
 * VoucherTransitionHttpResult은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface VoucherTransitionHttpResult {
/**
 * Transitioned은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property body 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 * @property positions 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */

    data class Transitioned(
        /**
         * `body`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val body: VoucherClaimHttpResponse,
        /**
         * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val replayed: Boolean,
        /**
         * `positions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val positions: ProjectionPositions,
    ): VoucherTransitionHttpResult
/**
 * Rejected은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property status 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property code 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    data class Rejected(
        /**
         * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        val status: Int,
        /**
         * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val code: String,
    ): VoucherTransitionHttpResult

    /**
     * InProgress은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object InProgress: VoucherTransitionHttpResult

    /**
     * KeyUnavailable은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object KeyUnavailable: VoucherTransitionHttpResult
}
/**
 * EventSourcedVoucherCommandController은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property service 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@RestController
@RequestMapping("/api/v1")
internal class EventSourcedVoucherCommandController(
    /**
     * `service`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: VoucherCommandHttpService,
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
     * null, idempotencyHeader = "demo-idempotency-1", body = body)
     * ```
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param principalHeader 요청 경계에서 검증된 `principalHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/campaigns/{campaignId}/claims")
    fun allocate(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: AllocateVoucherHttpRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = tenantHeader.requireNotBlank(TENANT_HEADER)
        /**
         * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val principal = principalHeader.requireNotBlank(PRINCIPAL_HEADER)
        /**
         * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val idempotencyKey = idempotencyHeader.requireNotBlank(IDEMPOTENCY_HEADER)

        return service.allocate(tenant, principal, idempotencyKey, campaignId, body).toResponse()
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
     * null, idempotencyHeader = "demo-idempotency-1", body = body)
     * ```
     * @param claimId 요청 경계에서 검증된 `claimId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param principalHeader 요청 경계에서 검증된 `principalHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/claims/{claimId}/redeem")
    fun redeem(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: RedeemVoucherHttpRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = tenantHeader.requireNotBlank(TENANT_HEADER)
        /**
         * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val principal = principalHeader.requireNotBlank(PRINCIPAL_HEADER)
        /**
         * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val idempotencyKey = idempotencyHeader.requireNotBlank(IDEMPOTENCY_HEADER)

        return service.redeem(tenant, principal, idempotencyKey, claimId, body).toResponse()
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
     * null, idempotencyHeader = "demo-idempotency-1", body = body)
     * ```
     * @param claimId 요청 경계에서 검증된 `claimId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param principalHeader 요청 경계에서 검증된 `principalHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/claims/{claimId}/release")
    fun release(
        @PathVariable claimId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: VoucherRevisionHttpRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = tenantHeader.requireNotBlank(TENANT_HEADER)
        /**
         * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val principal = principalHeader.requireNotBlank(PRINCIPAL_HEADER)
        /**
         * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val idempotencyKey = idempotencyHeader.requireNotBlank(IDEMPOTENCY_HEADER)

        return service.release(tenant, principal, idempotencyKey, claimId, body).toResponse()
    }
}

/**
 * toResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun VoucherAllocationHttpResult.toResponse(): ResponseEntity<Any> = when (this) {
    is VoucherAllocationHttpResult.Allocated   ->
        ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.LOCATION, "/api/v1/claims/${body.claimId}")
            .header("Idempotency-Replayed", replayed.toString())
            .header(STREAM_POSITION_HEADER, positions.streamPosition.toString())
            .header(PROJECTION_POSITION_HEADER, positions.projectionPosition.toString())
            .header(PROJECTION_LAG_HEADER, positions.lag.toString())
            .body(body)

    is VoucherAllocationHttpResult.Rejected    ->
        ResponseEntity.status(status)
            .body(EventSourcedApiError(code, "voucher allocation was rejected"))

    VoucherAllocationHttpResult.InProgress     ->
        ResponseEntity.status(HttpStatus.CONFLICT)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(EventSourcedApiError("COMMAND_IN_PROGRESS", "command is already in progress"))

    VoucherAllocationHttpResult.KeyUnavailable ->
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(EventSourcedApiError("REPLAY_KEY_UNAVAILABLE", "command replay key is unavailable"))
}

/**
 * toResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun VoucherTransitionHttpResult.toResponse(): ResponseEntity<Any> = when (this) {
    is VoucherTransitionHttpResult.Transitioned ->
        ResponseEntity.ok()
            .header("Idempotency-Replayed", replayed.toString())
            .header(STREAM_POSITION_HEADER, positions.streamPosition.toString())
            .header(PROJECTION_POSITION_HEADER, positions.projectionPosition.toString())
            .header(PROJECTION_LAG_HEADER, positions.lag.toString())
            .body(body)

    is VoucherTransitionHttpResult.Rejected     ->
        ResponseEntity.status(status)
            .body(EventSourcedApiError(code, "voucher transition was rejected"))

    VoucherTransitionHttpResult.InProgress      ->
        ResponseEntity.status(HttpStatus.CONFLICT)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(EventSourcedApiError("COMMAND_IN_PROGRESS", "command is already in progress"))

    VoucherTransitionHttpResult.KeyUnavailable  ->
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(EventSourcedApiError("REPLAY_KEY_UNAVAILABLE", "command replay key is unavailable"))
}

/**
 * toFailureCode은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ReceiptOutcome.toFailureCode(): String = when (this) {
    ReceiptOutcome.CAMPAIGN_NOT_FOUND      -> "CAMPAIGN_NOT_FOUND"
    ReceiptOutcome.CAMPAIGN_NOT_ACTIVE     -> "CAMPAIGN_NOT_ACTIVE"
    ReceiptOutcome.CAMPAIGN_NOT_STARTED    -> "CAMPAIGN_NOT_STARTED"
    ReceiptOutcome.CAMPAIGN_ENDED          -> "CAMPAIGN_ENDED"
    ReceiptOutcome.CAPACITY_EXHAUSTED      -> "CAPACITY_EXHAUSTED"
    ReceiptOutcome.PER_USER_LIMIT_REACHED  -> "PER_USER_LIMIT_REACHED"
    ReceiptOutcome.VOUCHER_NOT_FOUND       -> "CLAIM_NOT_FOUND"
    ReceiptOutcome.STALE_REVISION          -> "STALE_REVISION"
    ReceiptOutcome.INVALID_VOUCHER_CODE    -> "INVALID_CODE"
    ReceiptOutcome.VOUCHER_EXPIRED         -> "VOUCHER_EXPIRED"
    ReceiptOutcome.INVALID_TRANSITION      -> "INVALID_TRANSITION"
    ReceiptOutcome.CONCURRENT_MODIFICATION -> "CONCURRENT_MODIFICATION"
    else                                   -> "DOMAIN_REJECTED"
}
