package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.jsonMapper
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignAggregate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherAggregate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherCodeKeyVersions
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptScope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalDescriptor
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalKeyVersions
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventPage
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStorePort
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRead
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.HmacPurpose
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.KeyedDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentity
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentityService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.CAMPAIGN_STREAM_TYPE
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.CampaignEventCodec
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.SerializedCampaignEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.SerializedVoucherEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.VOUCHER_STREAM_TYPE
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.VoucherEventCodec
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.*

/**
 * `CAMPAIGN_SUBJECT_STREAM_TYPE`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
private const val CAMPAIGN_SUBJECT_STREAM_TYPE = "campaign-subject"
/**
 * `ALLOCATION_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val ALLOCATION_STATUS = 201
/**
 * `CONFLICT_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val CONFLICT_STATUS = 409
/**
 * `NOT_FOUND_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val NOT_FOUND_STATUS = 404
/**
 * `CODE_PAYLOAD_LENGTH`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
private const val CODE_PAYLOAD_LENGTH = 22
/**
 * `CODE_DIGEST_BYTES`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
private const val CODE_DIGEST_BYTES = 16
/**
 * AllocateVoucherCommandInput은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenant 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property principal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property userRef 호출 주체를 나타내는 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보나 비밀값은 저장·로그에 남기지 않습니다.
 */


internal data class AllocateVoucherCommandInput(
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
     * `userRef`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val userRef: String,
)

/**
 * VoucherCommandExecution은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface VoucherCommandExecution {
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
    ): VoucherCommandExecution

    /**
     * InProgress은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object InProgress: VoucherCommandExecution

    /**
     * FingerprintConflict은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
     * 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object FingerprintConflict: VoucherCommandExecution

    /**
     * KeyUnavailable은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object KeyUnavailable: VoucherCommandExecution
}

/**
 * EventSourcedVoucherCommands은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface EventSourcedVoucherCommands {
    /**
     * `allocate`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = allocate(input = input)
     * ```
     * @param input 요청 경계에서 검증된 `input`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun allocate(input: AllocateVoucherCommandInput): VoucherCommandExecution

    /**
     * `voucherCode`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = voucherCode(tenant = "tenant-a", campaignId = UUID.randomUUID(), allocationId
     * =
     * UUID.randomUUID(), keyVersion = 25)
     * ```
     * @param tenant 요청 경계에서 검증된 `tenant`입니다.
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @param allocationId 요청 경계에서 검증된 `allocationId`입니다.
     * @param keyVersion 요청 경계에서 검증된 `keyVersion`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun voucherCode(
        tenant: String,
        campaignId: UUID,
        allocationId: UUID,
        keyVersion: Int,
    ): String

    /**
     * `voucher`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = voucher(tenant = "tenant-a", voucherId = UUID.randomUUID())
     * ```
     * @param tenant 요청 경계에서 검증된 `tenant`입니다.
     * @param voucherId 요청 경계에서 검증된 `voucherId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun voucher(
        tenant: String,
        voucherId: UUID,
    ): VoucherAggregate?

    /**
     * `voucherInCurrentTransaction`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = voucherInCurrentTransaction(tenant = "tenant-a", voucherId =
     * UUID.randomUUID())
     * ```
     * @param tenant 요청 경계에서 검증된 `tenant`입니다.
     * @param voucherId 요청 경계에서 검증된 `voucherId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun voucherInCurrentTransaction(
        tenant: String,
        voucherId: UUID,
    ): VoucherAggregate? = voucher(tenant, voucherId)
}
/**
 * DefaultEventSourcedVoucherCommands은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property commands 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property events 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property identities 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property keyRing 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Suppress("TooManyFunctions")
internal class DefaultEventSourcedVoucherCommands(
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
): EventSourcedVoucherCommands {
    /**
     * 기술 구현을 위한 내부 상태이며 업무 데이터·비밀값을 외부 응답에 포함하지 않습니다.
     */
    private val mapper = jsonMapper { }
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private val campaignEvents = CampaignEventCodec()
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private val voucherEvents = VoucherEventCodec()

    /**
     * `allocate`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = allocate(input = input)
     * ```
     * @param input 요청 경계에서 검증된 `input`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun allocate(input: AllocateVoucherCommandInput): VoucherCommandExecution {
        val command = input.validated()
        val now = clock.instant()
        val tenantId = TenantId(command.tenant)
        val subject = identities.resolve(tenantId, "voucher-subject", command.userRef)

        val principalDigest = keyRing.digest(
            HmacPurpose.PRINCIPAL_SCOPE,
            tenantId,
            "voucher-allocate",
            command.principal
        )
        val idempotencyDigest = keyRing.digest(
            HmacPurpose.IDEMPOTENCY_KEY,
            tenantId,
            "voucher-allocate:${command.campaignId}:${principalDigest.value}",
            command.idempotencyKey,
        )
        val scope = ReceiptScope(
            tenantId = tenantId,
            principalDigest = ReceiptDigest.of(principalDigest.value),
            operation = "ALLOCATE",
            resourceId = command.campaignId.toString(),
            keyDigest = ReceiptDigest.of(idempotencyDigest.value),
        )
        return commands.execute(
            EventSourcedCommand(
                scope = scope,
                fingerprint = ReceiptDigest.sha256("voucher-allocate-v1\u0000${command.userRef}"),
                acquiredAt = now,
                decideAfterRehydrate = {
                    decideAllocation(command, tenantId, subject, principalDigest.keyVersion, now)
                },
            ),
        ).toVoucherExecution()
    }

    /**
     * `voucherCode`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = voucherCode(tenant = "tenant-a", campaignId = UUID.randomUUID(), allocationId
     * =
     * UUID.randomUUID(), keyVersion = 25)
     * ```
     * @param tenant 요청 경계에서 검증된 `tenant`입니다.
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @param allocationId 요청 경계에서 검증된 `allocationId`입니다.
     * @param keyVersion 요청 경계에서 검증된 `keyVersion`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun voucherCode(
        tenant: String,
        campaignId: UUID,
        allocationId: UUID,
        keyVersion: Int,
    ): String {
        val tenantId = TenantId(tenant.validCommandIdentity("tenant"))
        val digest: KeyedDigest = keyRing.digestWithVersion(
            keyVersion = keyVersion,
            purpose = HmacPurpose.VOUCHER_CODE,
            tenantId = tenantId,
            domain = "voucher-code:$campaignId",
            value = allocationId.toString(),
        )
        val payload = Base58
            .encode(HexFormat.of().parseHex(digest.value).copyOf(CODE_DIGEST_BYTES))
            .padStart(CODE_PAYLOAD_LENGTH, '1')

        return "V$keyVersion-${payload.takeLast(CODE_PAYLOAD_LENGTH)}"
    }

    /**
     * `voucher`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = voucher(tenant = "tenant-a", voucherId = UUID.randomUUID())
     * ```
     * @param tenant 요청 경계에서 검증된 `tenant`입니다.
     * @param voucherId 요청 경계에서 검증된 `voucherId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun voucher(
        tenant: String,
        voucherId: UUID,
    ): VoucherAggregate? =
        loadVoucher(tenant, voucherId, events::load)

    /**
     * `voucherInCurrentTransaction`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = voucherInCurrentTransaction(tenant = "tenant-a", voucherId =
     * UUID.randomUUID())
     * ```
     * @param tenant 요청 경계에서 검증된 `tenant`입니다.
     * @param voucherId 요청 경계에서 검증된 `voucherId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun voucherInCurrentTransaction(
        tenant: String,
        voucherId: UUID,
    ): VoucherAggregate? =
        loadVoucher(tenant, voucherId, events::loadInCurrentTransaction)

    /**
     * loadVoucher은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param voucherId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param load 업무 처리에 필요한 load 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun loadVoucher(
        tenant: String,
        voucherId: UUID,
        load: (EventStoreRead) -> EventPage,
    ): VoucherAggregate? {
        val tenantId = TenantId(tenant.validCommandIdentity("tenant"))
        val page = load(
            EventStoreRead(
                StreamKey(tenantId, VOUCHER_STREAM_TYPE, voucherId),
                afterVersion = 0,
            ),
        )
        return page.events
            .mapNotNull(voucherEvents::decode)
            .takeIf(List<VoucherEvent>::isNotEmpty)
            ?.let(VoucherAggregate::replay)
    }

    /**
     * decideAllocation은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param hmacKeyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun decideAllocation(
        command: AllocateVoucherCommandInput,
        tenantId: TenantId,
        subject: SubjectIdentity,
        hmacKeyVersion: Int,
        now: Instant,
    ): EventSourcedCommandDecision {
        val campaignStream = StreamKey(tenantId, CAMPAIGN_STREAM_TYPE, command.campaignId)
        val campaignPage = events.loadInCurrentTransaction(EventStoreRead(campaignStream, afterVersion = 0))

        return if (campaignPage.committedHead == 0L) {
            rejected(ReceiptOutcome.CAMPAIGN_NOT_FOUND, NOT_FOUND_STATUS, hmacKeyVersion, now)
        } else {
            val campaign = CampaignAggregate.replay(campaignPage.events.mapNotNull(campaignEvents::decode))
            val campaignRejection = campaign.rejectionAt(now)
            if (campaignRejection != null) {
                rejected(campaignRejection, CONFLICT_STATUS, hmacKeyVersion, now)
            } else {
                val subjectStream = StreamKey(
                    tenantId,
                    CAMPAIGN_SUBJECT_STREAM_TYPE,
                    subjectStreamId(command.campaignId, subject.surrogate),
                )
                val subjectPage = events.loadInCurrentTransaction(EventStoreRead(subjectStream, afterVersion = 0))
                if (subjectPage.committedHead >= campaign.perUserLimit) {
                    rejected(ReceiptOutcome.PER_USER_LIMIT_REACHED, CONFLICT_STATUS, hmacKeyVersion, now)
                } else {
                    acceptedAllocation(
                        context = AllocationDecisionContext(
                            command = command,
                            campaign =
                                CampaignAllocationAuthority(
                                    aggregate = campaign,
                                    stream = campaignStream,
                                    version = campaignPage.committedHead,
                                ),
                            subject =
                                SubjectAllocationAuthority(
                                    identity = subject,
                                    stream = subjectStream,
                                    version = subjectPage.committedHead,
                                ),
                            hmacKeyVersion = hmacKeyVersion,
                            now = now,
                        ),
                    )
                }
            }
        }
    }

    /**
     * acceptedAllocation은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param context 업무 처리에 필요한 context 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun acceptedAllocation(
        context: AllocationDecisionContext,
    ): EventSourcedCommandDecision {
        val command = context.command
        val campaign = context.campaign.aggregate
        val subject = context.subject.identity
        val now = context.now
        val voucherId = Uuid.V7.nextUUID()
        val expiresAt = minOf(now.plusSeconds(campaign.redemptionTtlSeconds), checkNotNull(campaign.endsAt))
        val reserved = CampaignEvent.VoucherCapacityReserved(voucherId, campaign.policyVersion)
        val issued = issuedVoucher(command, subject, voucherId, context.hmacKeyVersion)
        val allocated = VoucherEvent.VoucherAllocated(campaign.policyVersion, expiresAt)

        return EventSourcedCommandDecision(
            appends = listOf(
                ExpectedAppend(
                    context.campaign.stream,
                    context.campaign.version,
                    listOf(reserved.toAppend(subject, now)),
                ),
                ExpectedAppend(
                    StreamKey(TenantId(command.tenant), VOUCHER_STREAM_TYPE, voucherId),
                    0,
                    listOf(issued.toAppend(subject, now), allocated.toAppend(subject, now)),
                ),
                ExpectedAppend(
                    context.subject.stream,
                    context.subject.version,
                    listOf(
                        subjectAllocation(
                            mapper = mapper,
                            voucherId = voucherId,
                            campaignId = command.campaignId,
                            subject = subject,
                            now = now,
                        ),
                    ),
                ),
            ),
            descriptor = TerminalDescriptor(
                outcome = ReceiptOutcome.VOUCHER_ALLOCATED,
                status = ALLOCATION_STATUS,
                allocationId = voucherId,
                keyVersions =
                    TerminalKeyVersions(
                        hmac = context.hmacKeyVersion,
                        generationKeyVersion = context.hmacKeyVersion,
                        verificationKeyVersion = context.hmacKeyVersion,
                    ),
            ).withObservedAt(now),
        )
    }

    /**
     * toAppend은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun CampaignEvent.toAppend(
        subject: SubjectIdentity,
        now: Instant,
    ): EventToAppend = campaignEvents.encode(this).toAppend(subject, now)

    /**
     * toAppend은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun VoucherEvent.toAppend(
        subject: SubjectIdentity,
        now: Instant,
    ): EventToAppend = voucherEvents.encode(this).toAppend(subject, now)

    /**
     * toAppend은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun SerializedCampaignEvent.toAppend(
        subject: SubjectIdentity,
        now: Instant,
    ): EventToAppend = append(eventType, payload, subject, now)

    /**
     * toAppend은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun SerializedVoucherEvent.toAppend(
        subject: SubjectIdentity,
        now: Instant,
    ): EventToAppend = append(eventType, payload, subject, now)

    /**
     * append은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventType 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param payload 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun append(
        eventType: String,
        payload: EventPayload,
        subject: SubjectIdentity,
        now: Instant,
    ): EventToAppend {
        val eventId = Uuid.V7.nextUUID()
        return EventToAppend(
            eventId = eventId,
            eventType = eventType,
            schemaVersion = 1,
            payload = payload,
            occurredAt = now,
            correlationId = eventId.toString(),
            actorSurrogate = subject.surrogate.toString(),
            actorHmacKeyVersion = subject.hmacKeyVersion,
        )
    }
}
/**
 * CampaignAllocationAuthority은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property aggregate 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property stream 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


private data class CampaignAllocationAuthority(
    /**
     * `aggregate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val aggregate: CampaignAggregate,
    /**
     * `stream`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val stream: StreamKey,
    /**
     * `version`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: Long,
)
/**
 * SubjectAllocationAuthority은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property identity 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property stream 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


private data class SubjectAllocationAuthority(
    /**
     * `identity`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val identity: SubjectIdentity,
    /**
     * `stream`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val stream: StreamKey,
    /**
     * `version`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: Long,
)
/**
 * AllocationDecisionContext은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property command 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 * @property campaign 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property subject 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property hmacKeyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property now 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 */


private data class AllocationDecisionContext(
    /**
     * `command`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val command: AllocateVoucherCommandInput,
    /**
     * `campaign`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val campaign: CampaignAllocationAuthority,
    /**
     * `subject`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val subject: SubjectAllocationAuthority,
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
 * issuedVoucher은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
 * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
 * @param voucherId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param hmacKeyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun issuedVoucher(
    command: AllocateVoucherCommandInput,
    subject: SubjectIdentity,
    voucherId: UUID,
    hmacKeyVersion: Int,
): VoucherEvent.VoucherIssued =
    VoucherEvent.VoucherIssued(
        tenantId = TenantId(command.tenant),
        campaignId = command.campaignId,
        voucherId = voucherId,
        subjectId = subject.surrogate,
        codeKeyVersions = VoucherCodeKeyVersions(
            generation = hmacKeyVersion,
            verification = hmacKeyVersion,
        ),
    )

/**
 * rejected은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
 * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param hmacKeyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun rejected(
    outcome: ReceiptOutcome,
    status: Int,
    hmacKeyVersion: Int,
    now: Instant,
): EventSourcedCommandDecision =
    EventSourcedCommandDecision(
        appends = emptyList(),
        descriptor = TerminalDescriptor(
            outcome = outcome,
            status = status,
            keyVersions = TerminalKeyVersions(hmac = hmacKeyVersion),
        ).withObservedAt(now),
    )

/**
 * subjectAllocation은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param mapper 업무 처리에 필요한 mapper 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param voucherId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun subjectAllocation(
    mapper: JsonMapper,
    voucherId: UUID,
    campaignId: UUID,
    subject: SubjectIdentity,
    now: Instant,
): EventToAppend {
    /**
     * 업무 추적에 사용하는 불변 식별자입니다. 재시도와 비동기 전달에서도 같은 값을 유지합니다.
     */
    val eventId = Uuid.V7.nextUUID()
    return EventToAppend(
        eventId = eventId,
        eventType = "campaign-subject.voucher-allocated",
        schemaVersion = 1,
        payload = EventPayload(
            mapper.writeValueAsString(mapOf("campaignId" to campaignId, "voucherId" to voucherId)),
        ),
        occurredAt = now,
        correlationId = eventId.toString(),
        actorSurrogate = subject.surrogate.toString(),
        actorHmacKeyVersion = subject.hmacKeyVersion,
    )
}

/**
 * validated은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun AllocateVoucherCommandInput.validated(): AllocateVoucherCommandInput {
    val validPrincipal = principal.validCommandIdentity("principal")
    val validUserRef = userRef
        .validCommandIdentity("userRef")
        .requireEquals(validPrincipal, "userRef")

    return copy(
        tenant = tenant.validCommandIdentity("tenant"),
        principal = validPrincipal,
        idempotencyKey = idempotencyKey.validIdempotencyKey(),
        userRef = validUserRef,
    )
}

/**
 * rejectionAt은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun CampaignAggregate.rejectionAt(now: Instant): ReceiptOutcome? =
    when {
        state != CampaignState.ACTIVE       -> ReceiptOutcome.CAMPAIGN_NOT_ACTIVE
        now.isBefore(checkNotNull(startsAt)) -> ReceiptOutcome.CAMPAIGN_NOT_STARTED
        !now.isBefore(checkNotNull(endsAt)) -> ReceiptOutcome.CAMPAIGN_ENDED
        remainingCapacity <= 0              -> ReceiptOutcome.CAPACITY_EXHAUSTED
        else                                -> null
    }

/**
 * subjectStreamId은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param subjectId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
private fun subjectStreamId(
    campaignId: UUID,
    subjectId: UUID,
): UUID =
    UUID.nameUUIDFromBytes("$campaignId\u0000$subjectId".toUtf8Bytes())

/**
 * toVoucherExecution은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun CommandExecutionResult.toVoucherExecution(): VoucherCommandExecution =
    when (this) {
        is CommandExecutionResult.Executed       -> VoucherCommandExecution.Completed(descriptor, replayed = false)
        is CommandExecutionResult.Replayed       -> VoucherCommandExecution.Completed(descriptor, replayed = true)
        is CommandExecutionResult.InProgress     -> VoucherCommandExecution.InProgress
        is CommandExecutionResult.FingerprintConflict -> VoucherCommandExecution.FingerprintConflict
        is CommandExecutionResult.KeyUnavailable -> VoucherCommandExecution.KeyUnavailable
    }
