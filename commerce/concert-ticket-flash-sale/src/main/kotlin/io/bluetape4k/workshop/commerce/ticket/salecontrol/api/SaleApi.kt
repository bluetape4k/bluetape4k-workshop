package io.bluetape4k.workshop.commerce.ticket.salecontrol.api

import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import java.io.Serializable
import java.time.Instant
import java.util.*

/**
 * admission과 purchase에 노출되는 immutable sale policy snapshot입니다.
 * `policyVersion`은 화면에서 읽은 정책이 구매 transaction 안에서도 같은지 확인하는
 * optimistic guard입니다. 시간 창이 바뀌었으면 purchase는 snapshot을 재사용하지
 * 않고 다시 조회해야 합니다.
 * @property saleId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 콘서트 티켓 판매 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property opensAt 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property closesAt 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */
data class SalePolicySnapshot(
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val saleId: UUID,
    /**
     * `state`은 콘서트 티켓 플래시 세일에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: SaleState,
    /**
     * `policyVersion`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `opensAt`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val opensAt: Instant,
    /**
     * `closesAt`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val closesAt: Instant,
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
 * authoritative sale policy의 read boundary입니다. null은 sale ID가 없거나 고객에게
 * 공개할 수 없는 상태를 동일하게 표현할 수 있어 enumeration을 줄입니다.
 *
 * ```kotlin
 * val snapshot = saleQueries.get(saleId) ?: return
 * ```
 */
fun interface SaleQueries {
    /**
     * `get`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = get(saleId = Uuid.V7.nextId())
     * ```
     * @param saleId 요청 경계에서 검증된 `saleId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun get(saleId: UUID): SalePolicySnapshot?
}

/**
 * authoritative sale row의 shared lock 아래에서 읽는 purchase limit입니다.
 * `maxQuantity`, `perUserLimit`, `holdSeconds`는 구매 중에 바뀌지 않도록 sale row
 * lock과 같은 transaction에서 읽어야 합니다.
 * @property saleId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property perUserLimit 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property maxQuantity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property holdSeconds 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class SalePurchasePolicy(
    /**
     * `saleId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val saleId: UUID,
    /**
     * `policyVersion`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `perUserLimit`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val perUserLimit: Int,
    /**
     * `maxQuantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxQuantity: Int,
    /**
     * `holdSeconds`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val holdSeconds: Long,
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
 * purchase에서 사용하는 transaction-aware sale revalidation boundary입니다.
 *
 * ```kotlin
 * val limits = saleAuthority.requireOpen(tx, browserSnapshot, clock.instant())
 * // 이후 inventory hold와 limits 검증을 같은 tx에서 수행합니다.
 * ```
 */
fun interface SalePurchaseAuthority {
    /**
     * `requireOpen`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = requireOpen(transaction = transaction, expected = expected, now = now)
     * ```
     * @param transaction 요청 경계에서 검증된 `transaction`입니다.
     * @param expected 요청 경계에서 검증된 `expected`입니다.
     * @param now 요청 경계에서 검증된 `now`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun requireOpen(
        transaction: TicketJdbcTransaction,
        expected: SalePolicySnapshot,
        now: Instant,
    ): SalePurchasePolicy
}
