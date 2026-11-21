package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.jsonMapper
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignAggregate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignCommands
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.DomainTransitionException
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptScope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalDescriptor
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalKeyVersions
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStorePort
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRead
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.HmacPurpose
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentity
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentityService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.CAMPAIGN_STREAM_TYPE
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.CampaignEventCodec
import java.time.Clock
import java.time.Instant
import java.util.*

/**
 * `CAMPAIGN_CREATED_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val CAMPAIGN_CREATED_STATUS = 201
/**
 * `CAMPAIGN_TRANSITION_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val CAMPAIGN_TRANSITION_STATUS = 200
/**
 * `CAMPAIGN_CONFLICT_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val CAMPAIGN_CONFLICT_STATUS = 409
/**
 * CreateCampaignCommandInput은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenant 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property principal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property startsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property endsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 * @property capacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property perUserLimit 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property redemptionTtlSeconds 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


internal data class CreateCampaignCommandInput(
    /**
     * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val tenant: String,
    /**
     * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val principal: String,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
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
    /**
     * `capacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacity: Int,
    /**
     * `perUserLimit`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val perUserLimit: Int,
    /**
     * `redemptionTtlSeconds`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val redemptionTtlSeconds: Long,
)
/**
 * ActivateCampaignCommandInput은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenant 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property principal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class ActivateCampaignCommandInput(
    /**
     * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val tenant: String,
    /**
     * `principal`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val principal: String,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
)

/**
 * CampaignCommandExecution은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface CampaignCommandExecution {
/**
 * Completed은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property descriptor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */

    data class Completed(
        /**
         * `descriptor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val descriptor: TerminalDescriptor,
        /**
         * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val replayed: Boolean,
    ): CampaignCommandExecution

    /**
     * InProgress은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object InProgress: CampaignCommandExecution

    /**
     * FingerprintConflict은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
     * 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object FingerprintConflict: CampaignCommandExecution

    /**
     * KeyUnavailable은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object KeyUnavailable: CampaignCommandExecution
}
/**
 * CampaignActivationContext은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property stream 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property expectedVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property subject 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property hmacKeyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property now 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 */


private data class CampaignActivationContext(
    /**
     * `stream`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val stream: StreamKey,
    /**
     * `expectedVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedVersion: Long,
    /**
     * `subject`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val subject: SubjectIdentity,
    /**
     * `hmacKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val hmacKeyVersion: Int,
    /**
     * `now`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val now: Instant,
)

/**
 * EventSourcedCampaignCommands은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface EventSourcedCampaignCommands {
    /**
     * `create`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = create(input = input)
     * ```
     * @param input 요청 경계에서 검증된 `input`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun create(input: CreateCampaignCommandInput): CampaignCommandExecution

    /**
     * `activate`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = activate(input = input)
     * ```
     * @param input 요청 경계에서 검증된 `input`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun activate(input: ActivateCampaignCommandInput): CampaignCommandExecution

    /**
     * `campaign`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = campaign(tenant = "tenant-a", campaignId = UUID.randomUUID())
     * ```
     * @param tenant 요청 경계에서 검증된 `tenant`입니다.
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun campaign(
        tenant: String,
        campaignId: UUID,
    ): CampaignAggregate?
}

/**
 * 검증된 campaign command 하나를 generic receipt와 append orchestration boundary로 변환합니다.
 * @property commands 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property events 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property identities 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property keyRing 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class DefaultEventSourcedCampaignCommands(
    /**
     * `commands`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commands: EventSourcedCommandService,
    /**
     * `events`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val events: EventStorePort,
    /**
     * `identities`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val identities: SubjectIdentityService,
    /**
     * `keyRing`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val keyRing: EventSourcedHmacKeyRing,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock = Clock.systemUTC(),
): EventSourcedCampaignCommands {
    /**
     * 기술 구현을 위한 내부 상태이며 업무 데이터·비밀값을 외부 응답에 포함하지 않습니다.
     */
    private val mapper = jsonMapper { }
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private val campaignEvents = CampaignEventCodec()

    /**
     * `create`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = create(input = input)
     * ```
     * @param input 요청 경계에서 검증된 `input`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun create(input: CreateCampaignCommandInput): CampaignCommandExecution {
        val command = input.validated()
        val now = clock.instant()
        val tenantId = TenantId(command.tenant)
        val subject = identities.resolve(tenantId, "campaign-principal", command.principal)

        val principalDigest = keyRing.digest(
            purpose = HmacPurpose.PRINCIPAL_SCOPE,
            tenantId = tenantId,
            domain = "campaign-create",
            value = command.principal,
        )
        val idempotencyDigest = keyRing.digest(
            purpose = HmacPurpose.IDEMPOTENCY_KEY,
            tenantId = tenantId,
            domain = "campaign-create:${command.campaignId}:${principalDigest.value}",
            value = command.idempotencyKey,
        )
        val scope = ReceiptScope(
            tenantId = tenantId,
            principalDigest = ReceiptDigest.of(principalDigest.value),
            operation = "CAMPAIGN_CREATE",
            resourceId = command.campaignId.toString(),
            keyDigest = ReceiptDigest.of(idempotencyDigest.value),
        )
        val fingerprint = fingerprint(command)
        val created = command.toEvent(tenantId)

        return commands.execute(
            EventSourcedCommand(
                scope = scope,
                fingerprint = fingerprint,
                acquiredAt = now,
                decideAfterRehydrate = {
                    EventSourcedCommandDecision(
                        appends = listOf(
                            ExpectedAppend(
                                stream = StreamKey(tenantId, CAMPAIGN_STREAM_TYPE, command.campaignId),
                                expectedVersion = 0,
                                events = listOf(created.toAppend(subject, now)),
                            ),
                        ),
                        descriptor = TerminalDescriptor(
                            outcome = ReceiptOutcome.CAMPAIGN_CREATED,
                            status = CAMPAIGN_CREATED_STATUS,
                            keyVersions = TerminalKeyVersions(hmac = principalDigest.keyVersion),
                        ).withObservedAt(now),
                    )
                },
            ),
        ).toCampaignExecution()
    }

    /**
     * `activate`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = activate(input = input)
     * ```
     * @param input 요청 경계에서 검증된 `input`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun activate(input: ActivateCampaignCommandInput): CampaignCommandExecution {
        val command = input.validated()
        val now = clock.instant()
        val tenantId = TenantId(command.tenant)
        val subject = identities.resolve(tenantId, "campaign-principal", command.principal)

        val principalDigest = keyRing.digest(
            purpose = HmacPurpose.PRINCIPAL_SCOPE,
            tenantId = tenantId,
            domain = "campaign-activate",
            value = command.principal,
        )
        val idempotencyDigest = keyRing.digest(
            purpose = HmacPurpose.IDEMPOTENCY_KEY,
            tenantId = tenantId,
            domain = "campaign-activate:${command.campaignId}:${principalDigest.value}",
            value = command.idempotencyKey,
        )
        val scope = ReceiptScope(
            tenantId = tenantId,
            principalDigest = ReceiptDigest.of(principalDigest.value),
            operation = "CAMPAIGN_ACTIVATE",
            resourceId = command.campaignId.toString(),
            keyDigest = ReceiptDigest.of(idempotencyDigest.value),
        )
        return commands.execute(
            EventSourcedCommand(
                scope = scope,
                fingerprint = ReceiptDigest.sha256("voucher-campaign-activate-v1\u0000${command.expectedRevision}"),
                acquiredAt = now,
                decideAfterRehydrate = {
                    decideActivation(command, tenantId, subject, principalDigest.keyVersion, now)
                },
            ),
        ).toCampaignExecution()
    }

    /**
     * `campaign`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = campaign(tenant = "tenant-a", campaignId = UUID.randomUUID())
     * ```
     * @param tenant 요청 경계에서 검증된 `tenant`입니다.
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun campaign(
        tenant: String,
        campaignId: UUID,
    ): CampaignAggregate? {
        val tenantId = TenantId(tenant.requireNotBlank("tenant"))
        val stream = StreamKey(tenantId, CAMPAIGN_STREAM_TYPE, campaignId)
        val page = events.load(EventStoreRead(stream, afterVersion = 0))

        return page.events
            .mapNotNull(campaignEvents::decode)
            .takeIf(List<CampaignEvent>::isNotEmpty)
            ?.let(CampaignAggregate::replay)
    }

    /**
     * decideActivation은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param hmacKeyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun decideActivation(
        command: ActivateCampaignCommandInput,
        tenantId: TenantId,
        subject: SubjectIdentity,
        hmacKeyVersion: Int,
        now: Instant,
    ): EventSourcedCommandDecision {
        val stream = StreamKey(tenantId, CAMPAIGN_STREAM_TYPE, command.campaignId)
        val page = events.loadInCurrentTransaction(EventStoreRead(stream, afterVersion = 0))

        return when {
            page.committedHead == 0L ->
                rejectedDecision(ReceiptOutcome.CAMPAIGN_NOT_FOUND, hmacKeyVersion, now)
            page.committedHead != command.expectedRevision + 1 ->
                rejectedDecision(ReceiptOutcome.STALE_REVISION, hmacKeyVersion, now)
            else                     ->
                decideActivation(
                    campaign = CampaignAggregate.replay(page.events.mapNotNull(campaignEvents::decode)),
                    context = CampaignActivationContext(
                        stream = stream,
                        expectedVersion = page.committedHead,
                        subject = subject,
                        hmacKeyVersion = hmacKeyVersion,
                        now = now,
                    ),
                )
        }
    }

    /**
     * decideActivation은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param campaign 업무 처리에 필요한 campaign 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param context 업무 처리에 필요한 context 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun decideActivation(
        campaign: CampaignAggregate,
        context: CampaignActivationContext,
    ): EventSourcedCommandDecision =
        try {
            acceptedActivation(
                event = CampaignCommands.activate(campaign).events.single(),
                context = context,
            )
        } catch (_: DomainTransitionException) {
            rejectedDecision(ReceiptOutcome.DOMAIN_REJECTED, context.hmacKeyVersion, context.now)
        }

    /**
     * acceptedActivation은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param context 업무 처리에 필요한 context 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun acceptedActivation(
        event: CampaignEvent,
        context: CampaignActivationContext,
    ): EventSourcedCommandDecision =
        EventSourcedCommandDecision(
            appends = listOf(
                ExpectedAppend(
                    stream = context.stream,
                    expectedVersion = context.expectedVersion,
                    events = listOf(event.toAppend(context.subject, context.now)),
                ),
            ),
            descriptor = TerminalDescriptor(
                outcome = ReceiptOutcome.CAMPAIGN_ACTIVATED,
                status = CAMPAIGN_TRANSITION_STATUS,
                keyVersions = TerminalKeyVersions(hmac = context.hmacKeyVersion),
            ).withObservedAt(context.now),
        )

    /**
     * rejectedDecision은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param hmacKeyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun rejectedDecision(
        outcome: ReceiptOutcome,
        hmacKeyVersion: Int,
        now: Instant,
    ): EventSourcedCommandDecision =
        EventSourcedCommandDecision(
            appends = emptyList(),
            descriptor =
                TerminalDescriptor(
                    outcome = outcome,
                    status = CAMPAIGN_CONFLICT_STATUS,
                    keyVersions = TerminalKeyVersions(hmac = hmacKeyVersion),
                ).withObservedAt(now),
        )

    /**
     * fingerprint은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param input 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun fingerprint(input: CreateCampaignCommandInput): ReceiptDigest =
        ReceiptDigest.sha256(
            "voucher-campaign-create-v1\u0000" +
                    mapper.writeValueAsString(
                        CampaignCreatedPayload(
                            input.startsAt,
                            input.endsAt,
                            input.capacity,
                            input.perUserLimit,
                            input.redemptionTtlSeconds,
                        ),
                    ),
        )

    /**
     * toAppend은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param occurredAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun CampaignEvent.toAppend(
        subject: SubjectIdentity,
        occurredAt: Instant,
    ): EventToAppend {
        val eventId = Uuid.V7.nextUUID()
        val serialized = campaignEvents.encode(this)

        return EventToAppend(
            eventId = eventId,
            eventType = serialized.eventType,
            schemaVersion = 1,
            payload = serialized.payload,
            occurredAt = occurredAt,
            correlationId = eventId.toString(),
            actorSurrogate = subject.surrogate.toString(),
            actorHmacKeyVersion = subject.hmacKeyVersion,
        )
    }
}
/**
 * CampaignCreatedPayload은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property startsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property endsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 * @property capacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property perUserLimit 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property redemptionTtlSeconds 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


private data class CampaignCreatedPayload(
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
     * `capacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacity: Int,
    /**
     * `perUserLimit`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val perUserLimit: Int,
    /**
     * `redemptionTtlSeconds`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val redemptionTtlSeconds: Long,
)

/**
 * toEvent은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
 */
private fun CreateCampaignCommandInput.toEvent(tenantId: TenantId): CampaignEvent.CampaignCreated =
    CampaignEvent.CampaignCreated(
        tenantId = tenantId,
        campaignId = campaignId,
        startsAt = startsAt,
        endsAt = endsAt,
        capacity = capacity,
        perUserLimit = perUserLimit,
        redemptionTtlSeconds = redemptionTtlSeconds,
    )

/**
 * toCampaignExecution은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun CommandExecutionResult.toCampaignExecution(): CampaignCommandExecution =
    when (this) {
        is CommandExecutionResult.Executed       -> CampaignCommandExecution.Completed(
            descriptor,
            replayed = false
        )
        is CommandExecutionResult.Replayed       -> CampaignCommandExecution.Completed(descriptor, replayed = true)
        is CommandExecutionResult.InProgress     -> CampaignCommandExecution.InProgress
        is CommandExecutionResult.FingerprintConflict -> CampaignCommandExecution.FingerprintConflict
        is CommandExecutionResult.KeyUnavailable -> CampaignCommandExecution.KeyUnavailable
    }

/**
 * validated은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun CreateCampaignCommandInput.validated(): CreateCampaignCommandInput {
    val validKey = idempotencyKey.validIdempotencyKey()
    return copy(
        tenant = tenant.validCommandIdentity("tenant"),
        principal = principal.validCommandIdentity("principal"),
        idempotencyKey = validKey,
    )
}

/**
 * validated은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ActivateCampaignCommandInput.validated(): ActivateCampaignCommandInput =
    copy(
        tenant = tenant.validCommandIdentity("tenant"),
        principal = principal.validCommandIdentity("principal"),
        idempotencyKey = idempotencyKey.validIdempotencyKey(),
        expectedRevision = expectedRevision.requireZeroOrPositiveNumber("expectedRevision"),
    )
