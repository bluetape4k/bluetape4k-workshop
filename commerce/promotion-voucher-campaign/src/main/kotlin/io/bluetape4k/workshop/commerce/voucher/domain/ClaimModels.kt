package io.bluetape4k.workshop.commerce.voucher.domain

import java.time.Instant
import java.util.UUID

/**
 * `ClaimState`은 수명주기 상태입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class ClaimState {
    /** `ClaimState.ELIGIBLE` — voucher claim이 정책 검증을 통과해 allocation 후보가 된 상태입니다. */
    /** `ClaimState.ELIGIBLE` — voucher claim이 정책 검증을 통과해 allocation 후보가 된 상태입니다. */
    ELIGIBLE,
    /** `ClaimState.REVIEW_REQUIRED` — 자동 처리를 멈추고 allocation 또는 redemption 검토가 필요한 상태입니다. */
    /** `ClaimState.REVIEW_REQUIRED` — 자동 처리를 멈추고 allocation 또는 redemption 검토가 필요한 상태입니다. */
    REVIEW_REQUIRED,
    /** `ClaimState.ALLOCATED` — claim에 voucher와 capacity가 배정되어 중복 배정을 막는 상태입니다. */
    /** `ClaimState.ALLOCATED` — claim에 voucher와 capacity가 배정되어 중복 배정을 막는 상태입니다. */
    ALLOCATED,
    /** `ClaimState.REDEEMED` — 고객의 사용이 확정되어 claim을 다시 사용할 수 없는 상태입니다. */
    /** `ClaimState.REDEEMED` — 고객의 사용이 확정되어 claim을 다시 사용할 수 없는 상태입니다. */
    REDEEMED,
    /** `ClaimState.RELEASED` — 예약했던 capacity를 반환해 다른 claim이 사용할 수 있는 상태입니다. */
    /** `ClaimState.RELEASED` — 예약했던 capacity를 반환해 다른 claim이 사용할 수 있는 상태입니다. */
    RELEASED,
    /** `ClaimState.EXPIRED` — 유효기간이 지나 자동 사용이 거부되는 terminal 상태입니다. */
    /** `ClaimState.EXPIRED` — 유효기간이 지나 자동 사용이 거부되는 terminal 상태입니다. */
    EXPIRED,
    /** `ClaimState.REVOKED` — 운영 정책이나 부정 검증으로 claim의 효력을 회수한 terminal 상태입니다. */
    /** `ClaimState.REVOKED` — 운영 정책이나 부정 검증으로 claim의 효력을 회수한 terminal 상태입니다. */
    REVOKED,
    /** `ClaimState.REJECTED` — 업무 정책을 만족하지 못해 allocation 또는 redemption이 거부된 terminal 상태입니다. */
    /** `ClaimState.REJECTED` — 업무 정책을 만족하지 못해 allocation 또는 redemption이 거부된 terminal 상태입니다. */
    REJECTED,
}

/**
 * `ReviewKind`은 분류 코드입니다.
 * aggregate·event·resource의 종류를 구분해 저장 schema와 routing 규칙을 결정합니다.
 */
internal enum class ReviewKind {
    /** `ReviewKind.ALLOCATION` — voucher를 배정하기 전 위험 신호를 검토합니다. */
    /** `ReviewKind.ALLOCATION` — voucher를 배정하기 전 위험 신호를 검토합니다. */
    ALLOCATION,
    /** `ReviewKind.REDEMPTION` — voucher 사용 요청을 확정하기 전 검토합니다. */
    /** `ReviewKind.REDEMPTION` — voucher 사용 요청을 확정하기 전 검토합니다. */
    REDEMPTION,
}
/**
 * ClaimSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property claimId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property reviewKind 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property pendingFromState 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property capacityReserved 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property allocationPolicyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class ClaimSnapshot(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
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
    val state: ClaimState,
    /**
     * `reviewKind`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reviewKind: ReviewKind?,
    /**
     * `pendingFromState`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val pendingFromState: ClaimState?,
    /**
     * `capacityReserved`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacityReserved: Boolean,
    /**
     * `allocationPolicyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val allocationPolicyVersion: Long,
    /**
     * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant?,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
) {
    init {
        if (state == ClaimState.REVIEW_REQUIRED) {
            require(reviewKind != null && pendingFromState != null) {
                "review-required claim must preserve review context"
            }
        } else {
            require(reviewKind == null && pendingFromState == null) {
                "non-review claim cannot preserve review context"
            }
        }
    }
}
/**
 * TransitionOutcome은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property claim 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property capacityDelta 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 */


internal data class TransitionOutcome(
    /**
     * `claim`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val claim: ClaimSnapshot,
    /**
     * `capacityDelta`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val capacityDelta: Int,
)
