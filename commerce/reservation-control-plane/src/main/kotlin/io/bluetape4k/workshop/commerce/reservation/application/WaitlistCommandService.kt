package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import io.bluetape4k.workshop.commerce.reservation.domain.OfferState
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Clock
import java.time.Duration

/**
 * FIFO waitlist entry와 그 short-lived offer를 조율합니다.
 *
 * 점유된 capacity를 이전할 수 있는 mutation은 종속 waitlist 또는 offer row를 읽기 전에 resource row를 lock합니다.
 * 이로써 promotion, acceptance, expiry, release가 하나의 lock order를 공유합니다.
 * @property waitlists 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property offers 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property credentials 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property offerTtl 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property resources 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property holds 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audits 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Service
internal class WaitlistCommandService(
    /**
     * `waitlists`은 예약 제어면에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    private val waitlists: WaitlistEntryRepository,
    /**
     * `offers`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val offers: ReservationOfferRepository,
    /**
     * `credentials`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val credentials: ReservationCredentialService,
    /**
     * `clock`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
    @Value("\${reservation.offer.ttl:20s}") private val offerTtl: Duration = 20.seconds(),
    /**
     * `resources`은 예약 제어면에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    private val resources: CapacityResourceRepository? = null,
    /**
     * `holds`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val holds: ReservationHoldRepository? = null,
    /**
     * `audits`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val audits: ReservationAuditRepository? = null,
) {

    companion object: KLogging()

    /**
     * `join`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = join(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun join(command: JoinWaitlistCommand): WaitlistEntryRecord {
        resources?.findByIdForUpdate(command.resourceId)
            ?.also { resource ->
                if (resource.policyVersion != command.policyVersion) {
                    throw WaitlistCommandException("POLICY_VERSION_MISMATCH", resource.revision)
                }
                if (resource.revision != command.expectedResourceRevision) {
                    throw WaitlistCommandException("STALE_REVISION", resource.revision)
                }
                if (resource.occupiedCount < resource.capacity) {
                    throw WaitlistCommandException("CAPACITY_AVAILABLE", resource.revision)
                }
            }
        val entry = waitlists.join(
            resourceId = command.resourceId,
            ownerDigest = credentials.ownerDigest(command.ownerToken)
        )
        log.debug {
            "waitlist_join_applied entryId=${entry.id} resourceId=${entry.resourceId} sequence=${entry.sequence}"
        }
        audits?.record(
            aggregateType = "WAITLIST_ENTRY",
            aggregateId = entry.id,
            revision = entry.revision,
            outcome = entry.state.name
        )
        return entry
    }

    /**
     * `snapshot`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = snapshot(entryId = 1L, ownerToken = "demo")
     * ```
     * @param entryId 요청 경계에서 검증된 `entryId`입니다.
     * @param ownerToken 요청 경계에서 검증된 `ownerToken`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional(readOnly = true)
    fun snapshot(
        entryId: Long,
        ownerToken: String,
    ): WaitlistEntryRecord {
        val entry = waitlists.findById(entryId)
        requireOwner(ownerToken, entry.ownerDigest, entry.revision)
        return entry
    }

    /**
     * `cancel`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = cancel(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun cancel(command: CancelWaitlistCommand): WaitlistEntryRecord {
        val entry = waitlists.findById(command.entryId)
        requireOwner(command.ownerToken, entry.ownerDigest, entry.revision)

        if (entry.state != WaitlistState.WAITING) {
            throw WaitlistCommandException("INVALID_WAITLIST_STATE", entry.revision)
        }
        if (entry.revision != command.expectedRevision) {
            throw WaitlistCommandException("STALE_REVISION", entry.revision)
        }

        if (!waitlists.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = command.expectedRevision,
                from = WaitlistState.WAITING,
                to = WaitlistState.CANCELLED
            )
        ) {
            throw WaitlistCommandException("STALE_REVISION", entry.revision)
        }
        return waitlists.findById(entry.id)
            .also { updated ->
                audits?.record(
                    aggregateType = "WAITLIST_ENTRY",
                    aggregateId = updated.id,
                    revision = updated.revision,
                    outcome = updated.state.name
                )
            }
    }

    /**
     * 대기열에서 가장 오래 기다린 항목을 제안 상태로 승격합니다.
     *
     * caller transaction은 이 operation을 호출하기 전에 resource를 `FOR UPDATE`로 잠가야 합니다.
     * 대기열 row의 revision이 바뀌었거나 이미 다른 worker가 승격했다면 `null`을 반환하며,
     * 이 결과를 실패로 재시도하기보다 다음 대기열 항목과 현재 resource 상태를 다시 읽어야 합니다.
     * 예:
     * ```kotlin
     * val offer = waitlistCommands.promote(resourceId = 42L)
     * // offer == null이면 동시 worker가 먼저 처리했거나 대기자가 없는 경우다.
     * ```
     */
    @Transactional
    fun promote(resourceId: Long): ReservationOfferRecord? {
        resources?.findByIdForUpdate(resourceId)
        val entry = waitlists.oldestWaiting(resourceId) ?: return null
        if (!waitlists.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = entry.revision,
                from = WaitlistState.WAITING,
                to = WaitlistState.OFFERED
            )
        ) {
            log.debug {
                "waitlist_promotion_stale entryId=${entry.id} resourceId=$resourceId revision=${entry.revision}"
            }
            return null
        }
        val offer = offers.createActive(
            resourceId = resourceId,
            entryId = entry.id,
            ownerDigest = entry.ownerDigest,
            expiresAt = clock.instant().plus(offerTtl)
        )
        audits?.let {
            it.record(
                aggregateType = "WAITLIST_ENTRY",
                aggregateId = entry.id,
                revision = entry.revision + 1,
                outcome = WaitlistState.OFFERED.name
            )
            it.record(
                aggregateType = "RESERVATION_OFFER",
                aggregateId = offer.id,
                revision = offer.revision,
                outcome = offer.state.name
            )
        }

        log.debug { "waitlist_promotion_applied entryId=${entry.id} offerId=${offer.id} resourceId=$resourceId" }

        return offer
    }

    /**
     * `accept`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = accept(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun accept(command: AcceptOfferCommand): AcceptedOffer {
        val offerSnapshot = offers.findById(command.offerId)
        // resource lock을 잡은 뒤 offer를 다시 읽습니다. 첫 번째 read는 lock locator 역할만 합니다.
        val resource = resources?.findByIdForUpdate(offerSnapshot.resourceId)
        val offer = offers.findById(command.offerId)

        requireOwner(command.ownerToken, offer.ownerDigest, offer.revision)

        if (offer.state != OfferState.ACTIVE) {
            throw WaitlistCommandException("INVALID_OFFER_STATE", offer.revision)
        }
        if (!clock.instant().isBefore(offer.expiresAt)) {
            throw WaitlistCommandException("OFFER_EXPIRED", offer.revision)
        }
        if (offer.revision != command.expectedRevision) {
            throw WaitlistCommandException("STALE_REVISION", offer.revision)
        }
        val entry = waitlists.findById(offer.entryId)
        if (entry.resourceId != offer.resourceId || entry.ownerDigest != offer.ownerDigest ||
            entry.state != WaitlistState.OFFERED
        ) {
            throw WaitlistCommandException("OFFER_ENTRY_MISMATCH", offer.revision)
        }
        if (!offers.transition(
                id = offer.id,
                ownerDigest = offer.ownerDigest,
                expectedRevision = command.expectedRevision,
                from = OfferState.ACTIVE,
                to = OfferState.ACCEPTED
            )
        ) {
            throw WaitlistCommandException("STALE_REVISION", offer.revision)
        }
        if (!waitlists.transition(
                id = entry.id,
                ownerDigest = entry.ownerDigest,
                expectedRevision = entry.revision,
                from = WaitlistState.OFFERED,
                to = WaitlistState.ACCEPTED
            )
        ) {
            throw WaitlistCommandException("STALE_WAITLIST_REVISION", entry.revision)
        }
        val acceptedHold = if (holds != null && resource != null) {
            holds
                .create(
                    resourceId = resource.id,
                    ownerDigest = offer.ownerDigest,
                    policyVersion = resource.policyVersion,
                    expiresAt = offer.expiresAt
                )
                .also { created ->

                    holds.transition(
                        created.id,
                        offer.ownerDigest,
                        created.revision,
                        HoldState.HELD,
                        HoldState.CONFIRMED
                    ).checkBeTrue { "accepted offer hold transition failed" }
                }
                .let { created ->
                    holds.findById(created.id)
                }
        } else {
            null
        }

        val accepted = AcceptedOffer(offers.findById(offer.id), waitlists.findById(entry.id), acceptedHold)
        audits?.let {
            it.record(
                aggregateType = "RESERVATION_OFFER",
                aggregateId = accepted.offer.id,
                revision = accepted.offer.revision,
                outcome = accepted.offer.state.name
            )
            it.record(
                aggregateType = "WAITLIST_ENTRY",
                aggregateId = accepted.entry.id,
                revision = accepted.entry.revision,
                outcome = accepted.entry.state.name
            )
        }
        accepted.hold?.also { confirmed ->
            audits?.record(
                aggregateType = "RESERVATION_HOLD",
                aggregateId = confirmed.id,
                revision = 0,
                outcome = HoldState.HELD.name
            )
            audits?.record(
                aggregateType = "RESERVATION_HOLD",
                aggregateId = confirmed.id,
                revision = confirmed.revision,
                outcome = confirmed.state.name
            )
        }
        log.debug {
            "reservation_offer_accept_applied offerId=${offer.id} entryId=${entry.id} resourceId=${offer.resourceId}"
        }
        return accepted
    }

    /**
     * requireOwner은 예약 제어면 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param rawOwner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param expectedDigest 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun requireOwner(
        rawOwner: String,
        expectedDigest: String,
        revision: Long,
    ) {
        if (!credentials.matchesOwner(rawOwner, expectedDigest)) {
            throw WaitlistCommandException("OWNER_MISMATCH", revision)
        }
    }
}
/**
 * JoinWaitlistCommand은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property resourceId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property ownerToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property expectedResourceRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를
 * 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class JoinWaitlistCommand(
    /**
     * `resourceId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId: Long,
    /**
     * `ownerToken`은 예약 제어면에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerToken: String,
    /**
     * `expectedResourceRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedResourceRevision: Long = 0,
    /**
     * `policyVersion`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long = 1,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * CancelWaitlistCommand은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property entryId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property ownerToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class CancelWaitlistCommand(
    /**
     * `entryId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val entryId: Long,
    /**
     * `expectedRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `ownerToken`은 예약 제어면에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerToken: String,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * AcceptOfferCommand은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property offerId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property ownerToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class AcceptOfferCommand(
    /**
     * `offerId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val offerId: Long,
    /**
     * `expectedRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `ownerToken`은 예약 제어면에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerToken: String,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * AcceptedOffer은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property offer 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property entry 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property hold 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class AcceptedOffer(
    /**
     * `offer`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val offer: ReservationOfferRecord,
    /**
     * `entry`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val entry: WaitlistEntryRecord,
    /**
     * `hold`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val hold: ReservationHoldRecord? = null,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * WaitlistCommandException은 좌석 예약·대기열에서 사용하는 업무 객체입니다.
 * @property reason 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property currentRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 */


internal class WaitlistCommandException(
    /**
     * `reason`은 예약 제어면에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reason: String,
    /**
     * `currentRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val currentRevision: Long,
): RuntimeException(reason)
