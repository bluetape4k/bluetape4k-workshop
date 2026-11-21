@file:Suppress(
    "LongParameterList",
    "LongMethod", // Reservation keeps the ordered campaign, user-limit, candidate, effect, and audit transaction visible.
    "MagicNumber",
    "MaxLineLength",
    "TooGenericExceptionCaught", // Unexpected runtime failures must release the idempotency owner before propagating.
)

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.DescriptorAction
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCatalog
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.EffectReference
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyOwner
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.BatchRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.ReservationRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAuditRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigest
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.time.Instant
import java.util.*
/**
 * VoucherPoolLifecycleException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property code 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property safeEffectId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 * @property safeRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 */


internal class VoucherPoolLifecycleException(
    /**
     * `code`은 예약 제어면에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: VoucherPoolErrorCode,
    /**
     * `safeEffectId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val safeEffectId: UUID? = null,
    /**
     * `safeRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val safeRevision: Long? = null,
): IllegalStateException(code.name)
/**
 * ReserveVoucherCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property canonicalUser 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property reservationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */


internal data class ReserveVoucherCommand(
    /**
     * `tenantId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `canonicalUser`은 예약 제어면에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val canonicalUser: String,
    /**
     * `idempotencyKey`은 예약 제어면에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
    /**
     * `reservationId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val reservationId: UUID = Uuid.V7.nextId(),
)
/**
 * ReleaseReservationCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property reservationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property canonicalUser 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class ReleaseReservationCommand(
    /**
     * `tenantId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `reservationId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val reservationId: UUID,
    /**
     * `canonicalUser`은 예약 제어면에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val canonicalUser: String,
    /**
     * `expectedRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `idempotencyKey`은 예약 제어면에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
)
/**
 * ReservationSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property reservationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property batchId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property entryId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property entitlementRootId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property replacementOrdinal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class ReservationSnapshot(
    /**
     * `tenantId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `reservationId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val reservationId: UUID,
    /**
     * `campaignId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `batchId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID,
    /**
     * `entryId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val entryId: UUID,
    /**
     * `state`은 예약 제어면에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: ReservationState,
    /**
     * `expiresAt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant,
    /**
     * `entitlementRootId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val entitlementRootId: UUID?,
    /**
     * `replacementOrdinal`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replacementOrdinal: Int,
    /**
     * `policyVersion`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `revision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
)

/**
 * ReservationService은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface ReservationService {
    /**
     * `reserve`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = reserve(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun reserve(command: ReserveVoucherCommand): MutationResult<ReservationSnapshot>
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
    fun release(command: ReleaseReservationCommand): MutationResult<ReservationSnapshot>
}
/**
 * JdbcReservationService은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property repository 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property digests 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 */


internal class JdbcReservationService(
    executor: VoucherPoolJdbcExecutor,
    /**
     * `repository`은 예약 제어면에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: VoucherPoolRepository,
    idempotency: VoucherPoolIdempotencyRepository,
    /**
     * `digests`은 예약 제어면에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val digests: VoucherDigestService,
): ReservationService {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val mutations = LifecycleMutationExecutor(executor, idempotency)

    /**
     * `reserve`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = reserve(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun reserve(command: ReserveVoucherCommand): MutationResult<ReservationSnapshot> =
        mutations.execute(
            tenantId = command.tenantId,
            operation = "voucher-reserve",
            rawKey = command.idempotencyKey,
            fingerprint = fingerprint(
                "voucher-reserve",
                "campaignId" to command.campaignId,
                "canonicalUser" to command.canonicalUser,
            ),
            effectId = command.reservationId,
            successStatus = LIFECYCLE_HTTP_CREATED,
            lane = LifecycleLane.FOREGROUND,
        ) { connection, owner ->
            val keyVersion = repository.userIdentityKeyVersion(command.tenantId, command.campaignId)
                ?: fail(VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE)

            val userDigest: VoucherDigest = digests.userIdentity(
                command.tenantId,
                command.campaignId,
                command.canonicalUser,
                keyVersion
            )
            val guards = repository.lockReservationGuards(
                command.tenantId,
                command.campaignId,
                userDigest.copyBytes()
            ) ?: fail(VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE)
            requireActive(guards.campaign.state)

            val transactionTime = repository.transactionTime()
            if (transactionTime < guards.campaign.startsAt || transactionTime >= guards.campaign.endsAt) {
                fail(VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE)
            }
            val lockedEligibleBatchIds = eligibleBatchIds(guards.batches, transactionTime)
            if (lockedEligibleBatchIds.isEmpty()) failForUnavailableBatches(guards.batches, transactionTime)

            val used = guards.userLimit.activeReservations + guards.userLimit.activeAllocations
            if (used >= guards.campaign.perUserLimit || guards.userLimit.lifetimeConsumed >= guards.campaign.perUserLimit) {
                fail(VoucherPoolErrorCode.USER_LIMIT_REACHED)
            }

            val entry = repository.selectAvailableEntrySkipLocked(
                command.tenantId,
                command.campaignId,
                lockedEligibleBatchIds,
            ) ?: run {
                val eligibleEntry = repository.hasAvailableEligibleEntry(
                    tenantId = command.tenantId,
                    campaignId = command.campaignId,
                    lockedBatchIds = lockedEligibleBatchIds,
                )
                val code = if (eligibleEntry) {
                    VoucherPoolErrorCode.POOL_BUSY
                } else {
                    VoucherPoolErrorCode.POOL_EXHAUSTED
                }
                fail(code)
            }
            val expiresAt = transactionTime.plusSeconds(guards.campaign.reservationTtlSeconds)
            val saved = repository.createReservation(
                reservation = ReservationRecord(
                    command.tenantId,
                    command.reservationId,
                    command.campaignId,
                    entry.batchId,
                    entry.entryId,
                    DigestValue.of(userDigest.copyBytes()),
                    DigestValue.of(owner.copyScopedKeyDigest()),
                    ReservationState.ACTIVE.name,
                    expiresAt,
                    policyVersion = guards.campaign.policyVersion,
                    revision = 0,
                ),
                entry = entry,
                userLimit = guards.userLimit,
            )
            repository.appendLifecycleAudit(
                tenantId = saved.tenantId,
                campaignId = saved.campaignId,
                aggregateType = "RESERVATION",
                aggregateId = saved.reservationId,
                revision = saved.revision,
                policyVersion = saved.policyVersion,
                reasonCode = "RESERVED",
                actorType = "CUSTOMER",
            )
            saved.snapshot()
        }

    /**
     * eligibleBatchIds은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param batches 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param transactionTime 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun eligibleBatchIds(batches: List<BatchRecord>, transactionTime: Instant): List<UUID> =
        batches
            .filter { batch ->
                batch.state == BatchState.ACTIVE &&
                        batch.activatesAt <= transactionTime &&
                        (batch.expiresAt == null || batch.expiresAt > transactionTime)
            }
            .map { it.batchId }

    /**
     * failForUnavailableBatches은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param batches 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param transactionTime 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun failForUnavailableBatches(batches: List<BatchRecord>, transactionTime: Instant): Nothing {
        batches.firstOrNull { it.state != BatchState.ACTIVE }?.let { requireBatchUsable(it.state) }

        if (batches.any { it.expiresAt?.let { expiry -> expiry <= transactionTime } == true }) {
            fail(VoucherPoolErrorCode.BATCH_EXPIRED)
        }
        fail(VoucherPoolErrorCode.POOL_EXHAUSTED)
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
    override fun release(command: ReleaseReservationCommand): MutationResult<ReservationSnapshot> =
        mutations.execute(
            tenantId = command.tenantId,
            operation = "voucher-reservation-release",
            rawKey = command.idempotencyKey,
            fingerprint = fingerprint(
                "voucher-reservation-release",
                "campaignId" to command.campaignId,
                "reservationId" to command.reservationId,
                "canonicalUser" to command.canonicalUser,
                "expectedRevision" to command.expectedRevision,
            ),
            effectId = command.reservationId,
            successStatus = LIFECYCLE_HTTP_OK,
            lane = LifecycleLane.FOREGROUND,
        ) { connection, _ ->
            val keyVersion = repository.userIdentityKeyVersion(command.tenantId, command.campaignId)
                ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            val digest = digests.userIdentity(command.tenantId, command.campaignId, command.canonicalUser, keyVersion)
            val chain = repository.lockReservationChain(
                command.tenantId, command.reservationId, digest.copyBytes(),
            ) ?: fail(VoucherPoolErrorCode.WRONG_OWNER)

            requireCampaignUsable(chain.campaign.state)
            requireBatchUsable(chain.batch.state)

            if (chain.reservation.revision != command.expectedRevision) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.reservation.state != ReservationState.ACTIVE.name) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.reservation.expiresAt <= repository.transactionTime()) fail(VoucherPoolErrorCode.RESERVATION_EXPIRED)

            val released = repository.releaseReservation(chain)
            repository.appendLifecycleAudit(
                tenantId = released.tenantId,
                campaignId = released.campaignId,
                aggregateType = "RESERVATION",
                aggregateId = released.reservationId,
                revision = released.revision,
                policyVersion = released.policyVersion,
                reasonCode = "RELEASED",
                actorType = "CUSTOMER",
            )
            released.snapshot()
        }
}

/**
 * `LifecycleLane`은 업무 분기용 제한된 값 집합입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class LifecycleLane {
    /**
     * `LifecycleLane.FOREGROUND`은 호출자 요청과 같은 foreground 경로에서 즉시 수행하는 작업입니다.
     */
    FOREGROUND,
    /**
     * `LifecycleLane.OPERATOR`은 `operator` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    OPERATOR
}
/**
 * LifecycleMutationExecutor은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property executor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property idempotency 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class LifecycleMutationExecutor(
    /**
     * `executor`은 예약 제어면에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: VoucherPoolJdbcExecutor,
    /**
     * `idempotency`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val idempotency: VoucherPoolIdempotencyRepository,
) {
    /**
     * execute은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param rawKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param fingerprint 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param effectId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param successStatus 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param effect 업무 처리에 필요한 effect 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> execute(
        tenantId: String,
        operation: String,
        rawKey: String,
        fingerprint: CommandFingerprint,
        effectId: UUID,
        successStatus: Int,
        lane: LifecycleLane,
        effect: (Connection, IdempotencyOwner) -> T,
    ): MutationResult<T> {
        val scope = CommandScope(tenantId, operation)

        return when (val decision = transaction(lane) { idempotency.acquire(scope, rawKey, fingerprint) }) {
            is IdempotencyDecision.Execute    ->
                executeOwned(
                    owner = decision.owner,
                    effectId = effectId,
                    successStatus = successStatus,
                    lane = lane,
                    effect = effect
                )
            is IdempotencyDecision.Replay     -> MutationResult.Replay(decision.descriptor)
            is IdempotencyDecision.Expired    -> MutationResult.Expired(decision.effectId, decision.terminalCode)
            is IdempotencyDecision.InProgress -> fail(VoucherPoolErrorCode.COMMAND_IN_PROGRESS)
            IdempotencyDecision.FingerprintConflict -> fail(VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT)
        }
    }

    /**
     * executeOwned은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param effectId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param successStatus 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param effect 업무 처리에 필요한 effect 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun <T> executeOwned(
        owner: IdempotencyOwner,
        effectId: UUID,
        successStatus: Int,
        lane: LifecycleLane,
        effect: (Connection, IdempotencyOwner) -> T,
    ): MutationResult<T> {
        val outcome: OwnedLifecycleOutcome<T> = try {
            transaction(lane) {
                idempotency.lockOwnerForExecution(owner)
                try {
                    val applied = effect(currentConnection(), owner)
                    val descriptor = descriptor(applied, effectId, successStatus)
                    idempotency.finalize(owner, descriptor, EffectReference.effect(effectId))
                    OwnedLifecycleOutcome.Applied(applied)
                } catch (failure: CommitLifecycleFailure) {
                    idempotency.releaseRetryable(owner)
                    OwnedLifecycleOutcome.Failed(failure.lifecycleFailure)
                }
            }
        } catch (failure: VoucherPoolLifecycleException) {
            return handleLifecycleFailure(owner, lane, failure)
        } catch (failure: RuntimeException) {
            releaseRetryable(owner, lane)
            throw failure
        }

        return when (outcome) {
            is OwnedLifecycleOutcome.Applied -> MutationResult.Applied(outcome.value)
            is OwnedLifecycleOutcome.Failed -> throw outcome.failure
        }
    }

    /**
     * handleLifecycleFailure은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun <T> handleLifecycleFailure(
        owner: IdempotencyOwner,
        lane: LifecycleLane,
        failure: VoucherPoolLifecycleException,
    ): MutationResult<T> {
        val semantics = VoucherPoolErrorCatalog[failure.code]
        when (semantics.descriptorAction) {
            DescriptorAction.STORE      ->
                transaction(lane) {
                    idempotency.lockOwnerForExecution(owner)
                    idempotency.finalize(
                        owner,
                        SafeResponseDescriptor.terminal(semantics.httpStatus, failure.code),
                        EffectReference.terminal(failure.code),
                    )
                }
            DescriptorAction.RELEASE, DescriptorAction.NONE -> releaseRetryable(owner, lane)
            DescriptorAction.STORE_SAFE -> {
                val descriptor = SafeResponseDescriptor.success(
                    semantics.httpStatus,
                    failure.code.name,
                    checkNotNull(failure.safeEffectId),
                    checkNotNull(failure.safeRevision),
                )
                transaction(lane) {
                    idempotency.lockOwnerForExecution(owner)
                    idempotency.finalize(
                        owner,
                        descriptor,
                        EffectReference.effect(checkNotNull(failure.safeEffectId)),
                    )
                }
                return MutationResult.Replay(descriptor)
            }
        }
        throw failure
    }

    /**
     * descriptor은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param effectId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun descriptor(value: Any?, effectId: UUID, status: Int): SafeResponseDescriptor =
        when (value) {
            is ReservationSnapshot ->
                SafeResponseDescriptor.success(
                    status = status,
                    outcome = "RESERVATION_${value.state.name}",
                    effectId = effectId,
                    revision = value.revision
                )
            is AllocationSnapshot  ->
                SafeResponseDescriptor.success(
                    status = status,
                    outcome = "ALLOCATION_${value.state.name}",
                    effectId = effectId,
                    revision = value.revision
                )
            is RevealResult        -> SafeResponseDescriptor.success(status, value.outcome, effectId, value.revision)
            else                   -> error("unsupported lifecycle mutation result")
        }

    /**
     * releaseRetryable은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun releaseRetryable(owner: IdempotencyOwner, lane: LifecycleLane) {
        releaseRetryableOwner {
            transaction(lane) { idempotency.releaseRetryable(owner) }
        }
    }

    /**
     * transaction은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun <T> transaction(lane: LifecycleLane, block: () -> T): T =
        when (lane) {
            LifecycleLane.FOREGROUND -> executor.foregroundTransaction(block)
            LifecycleLane.OPERATOR   -> executor.operatorTransaction(block)
        }

}

/**
 * OwnedLifecycleOutcome은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
private sealed interface OwnedLifecycleOutcome<out T> {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    data class Applied<T>(val value: T): OwnedLifecycleOutcome<T>
/**
 * Failed은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property failure 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */

    data class Failed(val failure: VoucherPoolLifecycleException): OwnedLifecycleOutcome<Nothing>
}
/**
 * CommitLifecycleFailure은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property lifecycleFailure 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


private class CommitLifecycleFailure(
    /**
     * `lifecycleFailure`은 예약 제어면에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val lifecycleFailure: VoucherPoolLifecycleException,
): RuntimeException(lifecycleFailure)

/**
 * snapshot은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun ReservationRecord.snapshot(): ReservationSnapshot =
    ReservationSnapshot(
        tenantId = tenantId,
        reservationId = reservationId,
        campaignId = campaignId,
        batchId = batchId,
        entryId = entryId,
        state = ReservationState.valueOf(state),
        expiresAt = expiresAt,
        entitlementRootId = entitlementRootId,
        replacementOrdinal = replacementOrdinal,
        policyVersion = policyVersion,
        revision = revision,
    )

/**
 * requireActive은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
internal fun requireActive(state: CampaignState) {
    when (state) {
        CampaignState.ACTIVE  -> Unit
        CampaignState.PAUSED  -> fail(VoucherPoolErrorCode.CAMPAIGN_PAUSED)
        CampaignState.REVOKING -> fail(VoucherPoolErrorCode.CAMPAIGN_REVOKING)
        CampaignState.REVOKED -> fail(VoucherPoolErrorCode.CAMPAIGN_REVOKED)
        CampaignState.DRAFT   -> fail(VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE)
    }
}

/**
 * requireCampaignUsable은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
internal fun requireCampaignUsable(state: CampaignState) = requireActive(state)

/**
 * requireBatchUsable은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
internal fun requireBatchUsable(state: BatchState) {
    when (state) {
        BatchState.ACTIVE           -> Unit
        BatchState.PAUSED           -> fail(VoucherPoolErrorCode.BATCH_PAUSED)
        BatchState.EXPIRING         -> fail(VoucherPoolErrorCode.BATCH_EXPIRING)
        BatchState.REVOKED, BatchState.REVOKING -> fail(VoucherPoolErrorCode.BATCH_REVOKED)
        BatchState.EXPIRED          -> fail(VoucherPoolErrorCode.BATCH_EXPIRED)
        BatchState.FAILED_RETRYABLE -> fail(VoucherPoolErrorCode.BATCH_FAILED_RETRYABLE)
        BatchState.FAILED_TERMINAL  -> fail(VoucherPoolErrorCode.BATCH_FAILED_TERMINAL)
        BatchState.STAGING          -> fail(VoucherPoolErrorCode.SCOPE_NOT_FOUND)
    }
}

/**
 * fingerprint은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param fields 업무 처리에 필요한 fields 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun fingerprint(operation: String, vararg fields: Pair<String, Any?>): CommandFingerprint =
    VoucherPoolFingerprint.command(operation, fields.associate { it.first to it.second.toString() })

/**
 * currentConnection은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun currentConnection(): Connection =
    checkNotNull(TransactionManager.currentOrNull()?.connection?.connection as? Connection) {
        "voucher pool lifecycle requires an active JDBC transaction"
    }

/**
 * fail은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun fail(code: VoucherPoolErrorCode): Nothing = throw VoucherPoolLifecycleException(code)

/**
 * failSafe은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param effectId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
internal fun failSafe(code: VoucherPoolErrorCode, effectId: UUID, revision: Long): Nothing =
    throw VoucherPoolLifecycleException(code, effectId, revision)

/**
 * failCommitted은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun failCommitted(code: VoucherPoolErrorCode): Nothing =
    throw CommitLifecycleFailure(VoucherPoolLifecycleException(code))

/**
 * appendLifecycleAudit은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송
 * 결과를 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
 * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param aggregateType 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param aggregateId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 * @param policyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 * @param reasonCode 업무 처리에 필요한 reasonCode 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param actorType 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
 */
internal fun VoucherPoolRepository.appendLifecycleAudit(
    tenantId: String,
    campaignId: UUID,
    aggregateType: String,
    aggregateId: UUID,
    revision: Long,
    policyVersion: Long,
    reasonCode: String,
    actorType: String,
) {
    val auditRecord = VoucherPoolAuditRecord(
        tenantId = tenantId,
        campaignId = campaignId,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        revision = revision,
        policyVersion = policyVersion,
        actorType = actorType,
        reasonCode = reasonCode,
    )
    appendAudit(auditRecord)
}

/**
 * `LIFECYCLE_HTTP_OK`은 예약 제어면에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
internal const val LIFECYCLE_HTTP_OK = 200
/**
 * `LIFECYCLE_HTTP_CREATED`은 예약 제어면에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
internal const val LIFECYCLE_HTTP_CREATED = 201
