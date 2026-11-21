package io.bluetape4k.workshop.commerce.ticket.payment.api

import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import java.io.Serializable
import java.time.Instant
import java.util.*

/**
 * provider secret이 없는 operations-safe payment projection입니다.
 * `outcome == null`은 아직 provider가 답하지 않았다는 뜻이지 승인이나 실패가
 * 아닙니다. operator는 [nextReconcileAt]을 보고 재조회 시점을 판단하며 credential,
 * 카드 번호, raw provider payload는 이 타입으로 전달하지 않습니다.
 * @property operationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property outcome 현재 콘서트 티켓 판매 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property nextReconcileAt 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */
data class PaymentOperationSnapshot(
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val operationId: UUID,
    /**
     * `outcome`은 콘서트 티켓 플래시 세일에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val outcome: PaymentOutcome?,
    /**
     * `revision`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `nextReconcileAt`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val nextReconcileAt: Instant?,
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
 * payment reconciliation state의 read boundary입니다. 존재하지 않는 operation은
 * `null`이며, callback 처리나 provider 호출을 수행하지 않습니다.
 *
 * ```kotlin
 * val state = paymentQueries.operation(operationId)
 * // state?.outcome == null 이면 UNKNOWN/미확정으로 취급하고 재조정합니다.
 * ```
 */
fun interface PaymentQueries {
    /**
     * `operation`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = operation(operationId = Uuid.V7.nextId())
     * ```
     * @param operationId 요청 경계에서 검증된 `operationId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun operation(operationId: UUID): PaymentOperationSnapshot?
}
