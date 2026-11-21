package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireMatches
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import io.bluetape4k.workshop.commerce.reservation.domain.ReservationHoldSnapshot
import io.bluetape4k.workshop.commerce.reservation.domain.ReservationPolicies
import io.bluetape4k.workshop.commerce.reservation.domain.TransitionOutcome
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationHoldRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Clock
import java.time.Duration

/**
 * durable hold state transition을 소유합니다.
 *
 * PostgreSQL revision과 row lock이 모든 command outcome을 결정합니다. admission control은 이 service 앞에서
 * contention을 줄일 수 있지만, reservation을 authorize하거나 finalize하지 않습니다.
 * @property resources 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property holds 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property credentials 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property handoff 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audits 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Service
internal class ReservationCommandService(
    /**
     * `resources`은 예약 제어면에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    private val resources: CapacityResourceRepository,
    /**
     * `holds`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val holds: ReservationHoldRepository,
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
    /**
     * `handoff`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val handoff: ReservationCapacityHandoffService,
    /**
     * `audits`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val audits: ReservationAuditRepository,
) {
    companion object: KLogging() {
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        private val HOLD_TTL: Duration = 30.seconds()
    }

    /**
     * `hold`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = hold(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun hold(command: CreateHoldCommand): ReservationHoldRecord {
        val now = clock.instant()
        val resource = resources.findById(command.resourceId)
        if (resource.policyVersion != command.policyVersion) {
            throw ReservationCommandException("POLICY_VERSION_MISMATCH", resource.revision, false)
        }
        // capacity CAS와 hold insert가 이 transaction을 공유하므로, insert 실패가 occupancy를 누수하지 않습니다.
        if (!resources.tryOccupy(resource.id, command.expectedResourceRevision)) {
            throw ReservationCommandException("CAPACITY_EXHAUSTED_OR_STALE", resource.revision, true)
        }
        return holds
            .create(
                resourceId = resource.id,
                ownerDigest = credentials.ownerDigest(command.ownerToken),
                policyVersion = command.policyVersion,
                expiresAt = now.plus(HOLD_TTL)
            )
            .also {
                audits.record(
                    aggregateType = "CAPACITY_RESOURCE",
                    aggregateId = resource.id,
                    revision = command.expectedResourceRevision + 1,
                    outcome = "CAPACITY_OCCUPIED"
                )
                audits.record(
                    aggregateType = "RESERVATION_HOLD",
                    aggregateId = it.id,
                    revision = it.revision,
                    outcome = it.state.name
                )
                log.debug { "reservation_hold_command_applied holdId=${it.id} resourceId=${resource.id}" }
            }
    }

    /**
     * `confirm`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = confirm(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun confirm(command: MutateHoldCommand): ReservationHoldRecord =
        mutate(command, HoldState.CONFIRMED)

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
    fun cancel(command: MutateHoldCommand): ReservationHoldRecord =
        mutate(command, HoldState.CANCELLED)

    /**
     * `extend`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = extend(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun extend(command: ExtendHoldCommand): ReservationHoldRecord {
        val now = clock.instant()
        val current = holds.findById(command.holdId)
        val ownerDigest = credentials.ownerDigest(command.ownerToken)
        if (current.policyVersion != command.policyVersion) {
            throw ReservationCommandException("POLICY_VERSION_MISMATCH", current.revision, false)
        }
        val newExpiresAt = maxOf(current.expiresAt, now) + command.extendBySeconds.seconds()
        val outcome = ReservationPolicies.extend(
            hold = current.toSnapshot(),
            ownerDigest = ownerDigest,
            expectedRevision = command.expectedRevision,
            now = now,
            newExpiresAt = newExpiresAt
        )

        if (outcome is TransitionOutcome.Rejected) {
            throw ReservationCommandException(outcome.reason.name, outcome.currentRevision, false)
        }
        if (!holds.extend(
                id = current.id,
                ownerDigest = ownerDigest,
                expectedRevision = current.revision,
                expectedExpiresAt = current.expiresAt,
                newExpiresAt = newExpiresAt
            )
        ) {
            throw ReservationCommandException("STALE_REVISION", current.revision, true)
        }

        return holds.findById(current.id)
            .also { updated ->
                audits.record(
                    aggregateType = "RESERVATION_HOLD",
                    aggregateId = updated.id,
                    revision = updated.revision,
                    outcome = "EXTENDED"
                )
            }
    }

    /**
     * `forceRelease`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = forceRelease(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun forceRelease(command: ForceReleaseHoldCommand): ReservationHoldRecord {
        val now = clock.instant()
        val snapshot = holds.findById(command.holdId)
        // 모든 capacity handoff가 같은 global lock order를 따르도록 resource를 먼저 lock합니다.
        resources.findByIdForUpdate(snapshot.resourceId)

        val current = holds.findById(command.holdId)
        if (current.revision != command.expectedRevision) {
            throw ReservationCommandException("STALE_REVISION", current.revision, false)
        }
        if (current.state != HoldState.HELD) {
            throw ReservationCommandException("INVALID_STATE", current.revision, false)
        }
        holds.transition(
            current.id,
            current.ownerDigest,
            current.revision,
            HoldState.HELD,
            HoldState.RELEASED_BY_OPERATOR
        ).checkBeTrue { "operator hold transition lost" }

        val updated = holds.findById(current.id)
        audits.record(
            aggregateType = "RESERVATION_HOLD",
            aggregateId = updated.id,
            revision = updated.revision,
            outcome = updated.state.name,
            reason = command.reasonCode
        )
        handoff.promoteOrRelease(current.resourceId, now)
        log.debug {
            "reservation_operator_force_release holdId=${current.id} resourceId=${current.resourceId} " +
                    "reason=${command.reasonCode}"
        }
        return updated
    }

    /**
     * mutate은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 예약 제어면 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param target 업무 처리에 필요한 target 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun mutate(
        command: MutateHoldCommand,
        target: HoldState,
    ): ReservationHoldRecord {
        val now = clock.instant()
        val holdLocator = holds.findById(command.holdId)
        if (target == HoldState.CANCELLED) {
            // cancellation은 capacity를 waiter에게 넘길 수 있으므로 resource-first lock order에 참여합니다.
            resources.findByIdForUpdate(holdLocator.resourceId)
        }
        val current = holds.findById(command.holdId)
        val ownerDigest = credentials.ownerDigest(command.ownerToken)
        if (current.policyVersion != command.policyVersion) {
            throw ReservationCommandException("POLICY_VERSION_MISMATCH", current.revision, false)
        }
        val snapshot = current.toSnapshot()
        val outcome = when (target) {
            HoldState.CONFIRMED -> ReservationPolicies.confirm(snapshot, ownerDigest, command.expectedRevision, now)
            HoldState.CANCELLED -> ReservationPolicies.cancel(snapshot, ownerDigest, command.expectedRevision, now)
            else                -> error("unsupported target $target")
        }
        if (outcome is TransitionOutcome.Rejected) {
            throw ReservationCommandException(outcome.reason.name, outcome.currentRevision, false)
        }
        if (!holds.transition(current.id, ownerDigest, current.revision, HoldState.HELD, target)) {
            throw ReservationCommandException("STALE_REVISION", current.revision, true)
        }
        val updated = holds.findById(current.id)
        audits.record(
            aggregateType = "RESERVATION_HOLD",
            aggregateId = updated.id,
            revision = updated.revision,
            outcome = updated.state.name
        )
        if (target == HoldState.CANCELLED) {
            handoff.promoteOrRelease(current.resourceId, now)
        }
        return updated
    }
}
/**
 * CreateHoldCommand은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property resourceId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedResourceRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를
 * 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property ownerToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class CreateHoldCommand(
    /**
     * `resourceId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId: Long,
    /**
     * `expectedResourceRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedResourceRevision: Long,
    /**
     * `policyVersion`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
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
        private const val serialVersionUID = 1L
    }
}
/**
 * MutateHoldCommand은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property holdId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property ownerToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class MutateHoldCommand(
    /**
     * `holdId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val holdId: Long,
    /**
     * `expectedRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `policyVersion`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
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
        private const val serialVersionUID = 1L
    }
}
/**
 * ExtendHoldCommand은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property holdId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property extendBySeconds 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property ownerToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class ExtendHoldCommand(
    /**
     * `holdId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val holdId: Long,
    /**
     * `expectedRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `policyVersion`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `extendBySeconds`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val extendBySeconds: Long,
    /**
     * `ownerToken`은 예약 제어면에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerToken: String,
): Serializable {
    init {
        extendBySeconds.requireInRange(1, 300, "extendBySeconds")
    }

    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID = 1L
    }
}
/**
 * ForceReleaseHoldCommand은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property holdId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property reasonCode 현재 좌석 예약·대기열 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 */


internal data class ForceReleaseHoldCommand(
    /**
     * `holdId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val holdId: Long,
    /**
     * `expectedRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `reasonCode`은 예약 제어면에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reasonCode: String,
): Serializable {
    init {
        reasonCode.requireMatches(Regex("[A-Z0-9_]{3,40}"), "reasonCode")
    }

    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID = 1L
    }
}
/**
 * ReservationCommandException은 좌석 예약·대기열에서 사용하는 업무 객체입니다.
 * @property reason 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property currentRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property retryable 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property retryAfterSeconds 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class ReservationCommandException(
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
    val currentRevision: Long?,
    /**
     * `retryable`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryable: Boolean,
    /**
     * `retryAfterSeconds`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryAfterSeconds: Long? = null,
): RuntimeException(reason)

/**
 * toSnapshot은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ReservationHoldRecord.toSnapshot() =
    ReservationHoldSnapshot(
        id = id,
        resourceId = resourceId,
        ownerDigest = ownerDigest,
        state = state,
        revision = revision,
        policyVersion = policyVersion,
        expiresAt = expiresAt
    )
