package io.bluetape4k.workshop.commerce.reservation.domain

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/** current occupancy와 독립적으로 제어되는 availability lifecycle입니다. */
/** current occupancy와 독립적으로 제어되는 availability lifecycle입니다. */
enum class ResourceState {
    /** 신규 hold와 waitlist 참여를 허용합니다. */
    /** 신규 hold와 waitlist 참여를 허용합니다. */
    OPEN,
    /** 기존 hold는 조회할 수 있지만 신규 capacity 변경을 잠시 중단합니다. */
    /** 기존 hold는 조회할 수 있지만 신규 capacity 변경을 잠시 중단합니다. */
    PAUSED,
    /** resource가 영구적으로 닫혀 새로운 reservation을 받지 않습니다. */
    /** resource가 영구적으로 닫혀 새로운 reservation을 받지 않습니다. */
    CLOSED
}

/** capacity를 소유하는 reservation hold의 durable lifecycle입니다. */
/** capacity를 소유하는 reservation hold의 durable lifecycle입니다. */
enum class HoldState {
    /** capacity를 임시로 점유하고 만료 시각을 기다립니다. */
    /** capacity를 임시로 점유하고 만료 시각을 기다립니다. */
    HELD,
    /** 고객이 확정해 capacity를 계속 점유합니다. */
    /** 고객이 확정해 capacity를 계속 점유합니다. */
    CONFIRMED,
    /** 만료 worker가 capacity를 반환했습니다. */
    /** 만료 worker가 capacity를 반환했습니다. */
    EXPIRED,
    /** owner 또는 정책에 의해 취소되었습니다. */
    /** owner 또는 정책에 의해 취소되었습니다. */
    CANCELLED,
    /** operator가 고착 hold를 강제로 해제했습니다. */
    /** operator가 고착 hold를 강제로 해제했습니다. */
    RELEASED_BY_OPERATOR
}

/** durable FIFO entry lifecycle입니다. `OFFERED`는 capacity slot이 이 entry에 계속 예약되어 있음을 뜻합니다. */
/** durable FIFO entry lifecycle입니다. `OFFERED`는 capacity slot이 이 entry에 계속 예약되어 있음을 뜻합니다. */
enum class WaitlistState {
    /** FIFO 순서로 capacity를 기다립니다. */
    /** FIFO 순서로 capacity를 기다립니다. */
    WAITING,
    /** capacity가 이 entry의 bounded offer로 예약되었습니다. */
    /** capacity가 이 entry의 bounded offer로 예약되었습니다. */
    OFFERED,
    /** owner가 offer를 수락해 reservation으로 이어졌습니다. */
    /** owner가 offer를 수락해 reservation으로 이어졌습니다. */
    ACCEPTED,
    /** offer 또는 대기 시간이 지나 더 이상 승격할 수 없습니다. */
    /** offer 또는 대기 시간이 지나 더 이상 승격할 수 없습니다. */
    EXPIRED,
    /** owner가 waitlist 참여를 취소했습니다. */
    /** owner가 waitlist 참여를 취소했습니다. */
    CANCELLED
}

/** `OFFERED` waitlist entry와 짝을 이루는 short-lived offer lifecycle입니다. */
/** `OFFERED` waitlist entry와 짝을 이루는 short-lived offer lifecycle입니다. */
enum class OfferState {
    /** owner가 아직 수락할 수 있는 짧은 제안입니다. */
    /** owner가 아직 수락할 수 있는 짧은 제안입니다. */
    ACTIVE,
    /** owner가 제안을 수락했습니다. */
    /** owner가 제안을 수락했습니다. */
    ACCEPTED,
    /** 제안 시간이 지났습니다. */
    /** 제안 시간이 지났습니다. */
    EXPIRED,
    /** owner 또는 operator가 제안을 취소했습니다. */
    /** owner 또는 operator가 제안을 취소했습니다. */
    CANCELLED
}

/** caller에게 반환하고 audit event에 기록하는 안정적인 rejection vocabulary입니다. */
/** caller에게 반환하고 audit event에 기록하는 안정적인 rejection vocabulary입니다. */
enum class TransitionReason {
    /** 현재 hold/entry의 owner와 요청자가 다릅니다. */
    /** 현재 hold/entry의 owner와 요청자가 다릅니다. */
    OWNER_MISMATCH,
    /** caller가 읽은 revision보다 이미 상태가 앞섰습니다. */
    /** caller가 읽은 revision보다 이미 상태가 앞섰습니다. */
    STALE_REVISION,
    /** 정책 버전이 바뀌어 기존 입력을 적용할 수 없습니다. */
    /** 정책 버전이 바뀌어 기존 입력을 적용할 수 없습니다. */
    POLICY_VERSION_MISMATCH,
    /** 요청 대상 hold가 이미 만료되었습니다. */
    /** 요청 대상 hold가 이미 만료되었습니다. */
    HOLD_EXPIRED,
    /** 요청 대상 hold가 이미 취소되었습니다. */
    /** 요청 대상 hold가 이미 취소되었습니다. */
    HOLD_CANCELLED,
    /** operator가 이미 hold를 해제했습니다. */
    /** operator가 이미 hold를 해제했습니다. */
    HOLD_RELEASED_BY_OPERATOR,
    /** terminal confirmed hold를 다시 확정할 수 없습니다. */
    /** terminal confirmed hold를 다시 확정할 수 없습니다. */
    ALREADY_CONFIRMED,
    /** 만료 시각 또는 시간대 입력이 유효하지 않습니다. */
    /** 만료 시각 또는 시간대 입력이 유효하지 않습니다. */
    INVALID_EXPIRY,
    /** 현재 state에서 요청한 전이가 허용되지 않습니다. */
    /** 현재 state에서 요청한 전이가 허용되지 않습니다. */
    INVALID_STATE,
}

/**
 * database transaction 바깥에서 capacity policy를 평가할 때 사용하는 immutable input입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property capacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property occupiedCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */
data class CapacityResourceSnapshot(
    /**
     * `id`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
    /**
     * `capacity`은 예약 제어면에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacity: Int,
    /**
     * `occupiedCount`은 예약 제어면에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val occupiedCount: Int,
    /**
     * `revision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `policyVersion`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long = 1,
): Serializable {
    init {
        capacity.requirePositiveNumber("capacity")
        occupiedCount.requireInRange(0, capacity, "occupiedCount")
    }
    /**
     * `hasCapacity`은 예약 제어면에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */

    val hasCapacity: Boolean get() = occupiedCount < capacity

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
 * [ReservationPolicies]가 소비하는 immutable hold state입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property resourceId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property ownerDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property state 현재 좌석 예약·대기열 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property expiresAt 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */
data class ReservationHoldSnapshot(
    /**
     * `id`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
    /**
     * `resourceId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId: Long,
    /**
     * `ownerDigest`은 예약 제어면에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerDigest: String,
    /**
     * `state`은 예약 제어면에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: HoldState,
    /**
     * `revision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `policyVersion`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `expiresAt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant,
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

/** 해당 repository CAS와 함께 commit되어야 하는 policy result입니다. */
/** 해당 repository CAS와 함께 commit되어야 하는 policy result입니다. */
sealed interface TransitionOutcome {
/**
 * Applied은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property hold 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */

    data class Applied(
        /**
         * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val hold: ReservationHoldSnapshot,
    ): TransitionOutcome,
       Serializable {
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
 * Rejected은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property reason 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property currentRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


    data class Rejected(
        /**
         * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        val reason: TransitionReason,
        /**
         * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
         */
        val currentRevision: Long,
    ): TransitionOutcome,
       Serializable {
        companion object {
            /**
             * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID = 1L
        }
    }
}
