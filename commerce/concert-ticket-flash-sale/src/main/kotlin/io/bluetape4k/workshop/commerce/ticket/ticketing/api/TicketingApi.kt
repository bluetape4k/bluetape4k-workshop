package io.bluetape4k.workshop.commerce.ticket.ticketing.api

import io.bluetape4k.workshop.commerce.ticket.domain.TicketState
import java.io.Serializable
import java.time.Instant
import java.util.*

/**
 * operations-safe ticket effect projection입니다. 발급 provider의 secret이나 원문
 * 응답 대신 effect operation의 상태, revision, 마지막 변경 시각만 보여 줍니다.
 * `ISSUED`와 `REVOKED`만 durable ticket 결과로 간주할 수 있습니다.
 * @property operationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 콘서트 티켓 판매 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property updatedAt 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */
data class TicketEffectSnapshot(
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val operationId: UUID,
    /**
     * `state`은 콘서트 티켓 플래시 세일에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: TicketState,
    /**
     * `revision`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `updatedAt`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val updatedAt: Instant,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * ticket effect state의 read boundary입니다. `null`은 아직 effect operation이
 * 생성되지 않았거나 공개 read 범위 밖임을 뜻하며 외부 provider를 호출하지 않습니다.
 *
 * ```kotlin
 * val effect = ticketingQueries.effect(operationId)
 * if (effect?.state == TicketState.QUARANTINED) alertOperator(operationId)
 * ```
 */
fun interface TicketingQueries {
    /**
     * `effect`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = effect(operationId = Uuid.V7.nextId())
     * ```
     * @param operationId 요청 경계에서 검증된 `operationId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun effect(operationId: UUID): TicketEffectSnapshot?
}
