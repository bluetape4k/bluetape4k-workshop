package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.ActivateCampaignCommandInput
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.CampaignCommandExecution
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.CreateCampaignCommandInput
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedCampaignCommands
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignAggregate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalDescriptor
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.*

/**
 * `IDEMPOTENCY_HEADER`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
 *
 * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
 */
internal const val IDEMPOTENCY_HEADER = "Idempotency-Key"
/**
 * `MAX_CAMPAIGN_CAPACITY`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val MAX_CAMPAIGN_CAPACITY = 1_000_000L
/**
 * `MAX_PER_USER_LIMIT`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val MAX_PER_USER_LIMIT = 1_000L
/**
 * CreateCampaignHttpRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property startsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property endsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 * @property capacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property perUserLimit 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property redemptionTtlSeconds 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


internal data class CreateCampaignHttpRequest(
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
    @field:Min(1) @field:Max(MAX_CAMPAIGN_CAPACITY) val capacity: Int,
    @field:Min(1) @field:Max(MAX_PER_USER_LIMIT) val perUserLimit: Int,
    @field:Positive val redemptionTtlSeconds: Long,
)
/**
 * CampaignExpectedRevisionHttpRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class CampaignExpectedRevisionHttpRequest(
    @field:Min(0) val expectedRevision: Long,
)
/**
 * CampaignCommandHttpService은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property commands 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property snapshots 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */


internal class CampaignCommandHttpService(
    /**
     * `commands`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commands: EventSourcedCampaignCommands,
    /**
     * `snapshots`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val snapshots: CampaignProjectionSnapshotReader,
) {
    /**
     * create은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param body 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun create(
        tenant: String,
        principal: String,
        idempotencyKey: String,
        body: CreateCampaignHttpRequest,
    ): CampaignCreateHttpResult {
        val execution: CampaignCommandExecution = commands.create(
            CreateCampaignCommandInput(
                tenant = tenant,
                principal = principal,
                idempotencyKey = idempotencyKey,
                campaignId = body.campaignId,
                startsAt = body.startsAt,
                endsAt = body.endsAt,
                capacity = body.capacity,
                perUserLimit = body.perUserLimit,
                redemptionTtlSeconds = body.redemptionTtlSeconds,
            ),
        )
        return execution.toHttp(tenant, body)
    }

    /**
     * activate은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param principal 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param body 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun activate(
        tenant: String,
        principal: String,
        idempotencyKey: String,
        campaignId: UUID,
        body: CampaignExpectedRevisionHttpRequest,
    ): CampaignCreateHttpResult {
        val execution: CampaignCommandExecution = commands.activate(
            ActivateCampaignCommandInput(
                tenant = tenant,
                principal = principal,
                idempotencyKey = idempotencyKey,
                campaignId = campaignId,
                expectedRevision = body.expectedRevision,
            ),
        )
        return execution.toHttp(tenant, campaignId)
    }

    /**
     * toHttp은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param body 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun CampaignCommandExecution.toHttp(
        tenant: String,
        body: CreateCampaignHttpRequest,
    ): CampaignCreateHttpResult =
        when (this) {
            is CampaignCommandExecution.Completed   -> {
                if (descriptor.outcome != ReceiptOutcome.CAMPAIGN_CREATED) {
                    descriptor.toRejected()
                } else {
                    /**
                     * `streamPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
                     *
                     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
                     */
                    val streamPosition = checkNotNull(descriptor.streamPosition)
                    /**
                     * `projectionPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
                     *
                     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
                     */
                    val projectionPosition = snapshots
                        .read(TenantId(tenant), body.campaignId)
                        .positions.projectionPosition
                        .coerceAtMost(streamPosition)

                    CampaignCreateHttpResult.Completed(
                        campaign = CampaignHttpResponse(
                            campaignId = body.campaignId,
                            state = "DRAFT",
                            revision = 0,
                            policyVersion = 0,
                            capacity = body.capacity,
                            allocatedCount = 0,
                            remainingCapacity = body.capacity,
                            startsAt = body.startsAt,
                            endsAt = body.endsAt,
                            observedAt = checkNotNull(descriptor.observedAt),
                        ),
                        positions = ProjectionPositions(streamPosition, projectionPosition),
                        replayed = replayed,
                        status = HttpStatus.CREATED,
                    )
                }
            }

            CampaignCommandExecution.FingerprintConflict ->
                CampaignCreateHttpResult.Rejected(HttpStatus.CONFLICT, "IDEMPOTENCY_FINGERPRINT_CONFLICT")

            CampaignCommandExecution.InProgress     -> CampaignCreateHttpResult.InProgress
            CampaignCommandExecution.KeyUnavailable -> CampaignCreateHttpResult.KeyUnavailable
        }

    /**
     * toHttp은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun CampaignCommandExecution.toHttp(
        tenant: String,
        campaignId: UUID,
    ): CampaignCreateHttpResult =
        when (this) {
            is CampaignCommandExecution.Completed   ->
                if (descriptor.outcome == ReceiptOutcome.CAMPAIGN_ACTIVATED) {
                    /**
                     * `campaign`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                     *
                     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                     */
                    val campaign = checkNotNull(commands.campaign(tenant, campaignId))
                    /**
                     * `streamPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
                     *
                     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
                     */
                    val streamPosition = checkNotNull(descriptor.streamPosition)
                    /**
                     * `projectionPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
                     *
                     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
                     */
                    val projectionPosition = snapshots
                        .read(TenantId(tenant), campaignId)
                        .positions.projectionPosition
                        .coerceAtMost(streamPosition)
                    CampaignCreateHttpResult.Completed(
                        campaign = campaign.toHttp(checkNotNull(descriptor.observedAt)),
                        positions = ProjectionPositions(streamPosition, projectionPosition),
                        replayed = replayed,
                        status = HttpStatus.OK,
                    )
                } else {
                    descriptor.toRejected()
                }

            CampaignCommandExecution.FingerprintConflict ->
                CampaignCreateHttpResult.Rejected(HttpStatus.CONFLICT, "IDEMPOTENCY_FINGERPRINT_CONFLICT")

            CampaignCommandExecution.InProgress     -> CampaignCreateHttpResult.InProgress
            CampaignCommandExecution.KeyUnavailable -> CampaignCreateHttpResult.KeyUnavailable
        }

    /**
     * toRejected은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun TerminalDescriptor.toRejected(): CampaignCreateHttpResult.Rejected =
        when (outcome) {
            ReceiptOutcome.CAMPAIGN_NOT_FOUND ->
                CampaignCreateHttpResult.Rejected(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND")
            ReceiptOutcome.STALE_REVISION     ->
                CampaignCreateHttpResult.Rejected(HttpStatus.CONFLICT, "STALE_REVISION")
            ReceiptOutcome.CONCURRENT_MODIFICATION ->
                CampaignCreateHttpResult.Rejected(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION")
            ReceiptOutcome.DOMAIN_REJECTED    ->
                CampaignCreateHttpResult.Rejected(HttpStatus.CONFLICT, "CAMPAIGN_NOT_ACTIVE")
            else                              ->
                CampaignCreateHttpResult.Rejected(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION")
        }
}

/**
 * CampaignCreateHttpResult은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface CampaignCreateHttpResult {
/**
 * Completed은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property campaign 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property positions 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 * @property status 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 */

    data class Completed(
        /**
         * `campaign`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val campaign: CampaignHttpResponse,
        /**
         * `positions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val positions: ProjectionPositions,
        /**
         * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val replayed: Boolean,
        /**
         * `status`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        val status: HttpStatus,
    ): CampaignCreateHttpResult
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
        val status: HttpStatus,
        /**
         * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val code: String,
    ): CampaignCreateHttpResult

    /**
     * InProgress은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object InProgress: CampaignCreateHttpResult

    /**
     * KeyUnavailable은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object KeyUnavailable: CampaignCreateHttpResult
}
/**
 * EventSourcedCampaignCommandController은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property service 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@RestController
@RequestMapping("/operator/api/v1")
internal class EventSourcedCampaignCommandController(
    /**
     * `service`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: CampaignCommandHttpService,
) {

    private companion object: KLogging()

    /**
     * `create`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = create(tenantHeader = "tenant-a", principalHeader = null, idempotencyHeader =
     * "demo-idempotency-1", ifNoneMatch = "demo", body = body)
     * ```
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param principalHeader 요청 경계에서 검증된 `principalHeader`입니다.
     * @param idempotencyHeader 요청 경계에서 검증된 `idempotencyHeader`입니다.
     * @param ifNoneMatch 요청 경계에서 검증된 `ifNoneMatch`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/campaigns")
    fun create(
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        @Valid @RequestBody body: CreateCampaignHttpRequest,
    ): ResponseEntity<Any> {

        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = tenantHeader
            .requireNotNull(TENANT_HEADER)
            .requireNotBlank(TENANT_HEADER)
        /**
         * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val principal = principalHeader
            .requireNotNull(PRINCIPAL_HEADER)
            .requireNotBlank(PRINCIPAL_HEADER)
        /**
         * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val idempotencyKey = idempotencyHeader
            .requireNotNull(IDEMPOTENCY_HEADER)
            .requireNotBlank(IDEMPOTENCY_HEADER)
        ifNoneMatch
            .requireNotNull(HttpHeaders.IF_NONE_MATCH)
            .requireEquals("*", HttpHeaders.IF_NONE_MATCH)

        return service.create(tenant, principal, idempotencyKey, body).toResponse()
    }

    /**
     * `activate`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = activate(campaignId = UUID.randomUUID(), tenantHeader = "tenant-a",
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
    @PostMapping("/campaigns/{campaignId}/activate")
    fun activate(
        @org.springframework.web.bind.annotation.PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader(IDEMPOTENCY_HEADER, required = false) idempotencyHeader: String?,
        @Valid @RequestBody body: CampaignExpectedRevisionHttpRequest,
    ): ResponseEntity<Any> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = tenantHeader
            .requireNotNull(TENANT_HEADER)
            .requireNotBlank(TENANT_HEADER)
        /**
         * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val principal = principalHeader
            .requireNotNull(PRINCIPAL_HEADER)
            .requireNotBlank(PRINCIPAL_HEADER)
        /**
         * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val idempotencyKey = idempotencyHeader
            .requireNotNull(IDEMPOTENCY_HEADER)
            .requireNotBlank(IDEMPOTENCY_HEADER)
        
        return service.activate(tenant, principal, idempotencyKey, campaignId, body).toResponse()
    }
}

/**
 * toHttp은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param observedAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun CampaignAggregate.toHttp(observedAt: Instant): CampaignHttpResponse =
    CampaignHttpResponse(
        campaignId = checkNotNull(campaignId),
        state = state.name,
        revision = version - 1,
        policyVersion = policyVersion - 1,
        capacity = capacity,
        allocatedCount = allocatedCount,
        remainingCapacity = remainingCapacity,
        startsAt = checkNotNull(startsAt),
        endsAt = checkNotNull(endsAt),
        observedAt = observedAt,
    )

/**
 * toResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun CampaignCreateHttpResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is CampaignCreateHttpResult.Completed ->
            ResponseEntity.status(status)
                .apply {
                    if (status == HttpStatus.CREATED) {
                        header(HttpHeaders.LOCATION, "/api/v1/campaigns/${campaign.campaignId}")
                    } else {
                        eTag("\"${campaign.revision}\"")
                    }
                }
                .header(STREAM_POSITION_HEADER, positions.streamPosition.toString())
                .header(PROJECTION_POSITION_HEADER, positions.projectionPosition.toString())
                .header(PROJECTION_LAG_HEADER, positions.lag.toString())
                .header("Idempotency-Replayed", replayed.toString())
                .body(campaign)

        is CampaignCreateHttpResult.Rejected  ->
            ResponseEntity.status(status)
                .body(EventSourcedApiError(code, "command conflicts with current state"))

        CampaignCreateHttpResult.InProgress   ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(EventSourcedApiError("COMMAND_IN_PROGRESS", "command is already in progress"))

        CampaignCreateHttpResult.KeyUnavailable ->
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(EventSourcedApiError("REPLAY_KEY_UNAVAILABLE", "command replay key is unavailable"))
    }
