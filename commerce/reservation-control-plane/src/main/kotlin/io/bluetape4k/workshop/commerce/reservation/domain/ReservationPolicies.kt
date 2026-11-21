package io.bluetape4k.workshop.commerce.reservation.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.time.Instant

/**
 * persistence를 수행하지 않고 결정적인 hold transition policy를 평가합니다.
 *
 * 성공 decision은 repository가 일치하는 PostgreSQL CAS를 적용할 때까지 advisory입니다.
 */
object ReservationPolicies: KLogging() {

    /**
     * confirm은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param hold 업무 처리에 필요한 hold 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun confirm(
        hold: ReservationHoldSnapshot,
        ownerDigest: String,
        expectedRevision: Long,
        now: Instant,
    ): TransitionOutcome =
        transition(hold, ownerDigest, expectedRevision, now, HoldState.CONFIRMED)

    /**
     * cancel은 예약 제어면의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param hold 업무 처리에 필요한 hold 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun cancel(
        hold: ReservationHoldSnapshot,
        ownerDigest: String,
        expectedRevision: Long,
        now: Instant,
    ): TransitionOutcome =
        transition(hold, ownerDigest, expectedRevision, now, HoldState.CANCELLED)

    /**
     * extend은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param hold 업무 처리에 필요한 hold 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param newExpiresAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun extend(
        hold: ReservationHoldSnapshot,
        ownerDigest: String,
        expectedRevision: Long,
        now: Instant,
        newExpiresAt: Instant,
    ): TransitionOutcome {
        val transition = transition(hold, ownerDigest, expectedRevision, now, HoldState.HELD)
        if (transition is TransitionOutcome.Rejected) {
            return transition
        }
        if (!newExpiresAt.isAfter(hold.expiresAt)) {
            log.debug {
                "reservation_transition_rejected holdId=${hold.id} revision=${hold.revision} reason=INVALID_EXPIRY"
            }
            return TransitionOutcome.Rejected(TransitionReason.INVALID_EXPIRY, hold.revision)
        }
        val extended = hold.copy(revision = hold.revision + 1, expiresAt = newExpiresAt)
        log.debug { "reservation_transition_applied holdId=${hold.id} revision=${extended.revision} state=EXTENDED" }

        return TransitionOutcome.Applied(extended)
    }

    /**
     * occupiedAfterOfferAccepted은 예약 제어면 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param resource 업무 처리에 필요한 resource 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun occupiedAfterOfferAccepted(resource: CapacityResourceSnapshot): Int = resource.occupiedCount

    /**
     * transition은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param hold 업무 처리에 필요한 hold 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param target 업무 처리에 필요한 target 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun transition(
        hold: ReservationHoldSnapshot,
        ownerDigest: String,
        expectedRevision: Long,
        now: Instant,
        target: HoldState,
    ): TransitionOutcome {
        val rejection = when {
            hold.ownerDigest != ownerDigest              -> TransitionReason.OWNER_MISMATCH
            hold.revision != expectedRevision            -> TransitionReason.STALE_REVISION
            hold.state == HoldState.CONFIRMED            -> TransitionReason.ALREADY_CONFIRMED
            hold.state == HoldState.CANCELLED            -> TransitionReason.HOLD_CANCELLED
            hold.state == HoldState.RELEASED_BY_OPERATOR -> TransitionReason.HOLD_RELEASED_BY_OPERATOR
            hold.state != HoldState.HELD                 -> TransitionReason.INVALID_STATE
            !now.isBefore(hold.expiresAt)                -> TransitionReason.HOLD_EXPIRED
            else                                         -> null
        }
        
        if (rejection != null) {
            log.debug {
                "reservation_transition_rejected holdId=${hold.id} revision=${hold.revision} reason=$rejection"
            }
            return TransitionOutcome.Rejected(rejection, hold.revision)
        }

        val changed = hold.copy(state = target, revision = hold.revision + 1)
        log.debug { "reservation_transition_applied holdId=${hold.id} revision=${changed.revision} state=$target" }
        return TransitionOutcome.Applied(changed)
    }
}
