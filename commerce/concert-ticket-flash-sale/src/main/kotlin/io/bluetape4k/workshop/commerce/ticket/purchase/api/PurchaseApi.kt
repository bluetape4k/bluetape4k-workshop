package io.bluetape4k.workshop.commerce.ticket.purchase.api

import io.bluetape4k.workshop.commerce.ticket.admission.api.ConsumeGrant
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.domain.TicketDisposition
import io.bluetape4k.workshop.commerce.ticket.domain.TicketEffectOutcome
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePolicySnapshot
import java.io.Serializable
import java.time.Instant
import java.util.*

/**
 * 참조한 sale policy 아래에서 owner-scoped purchase 하나를 시작하는 command입니다.
 *
 * `policy`는 화면에서 읽은 값일 뿐이며 실제 transaction 안에서 다시 검증됩니다.
 * `attemptId`와 `authorizationOperationId`는 재시도 때 같은 업무 시도를 식별하고,
 * `grant`는 waiting-room admission을 purchase에 연결합니다. 따라서 이 DTO를
 * 재사용해 다른 구매자를 대신하거나 다른 sale policy를 우회할 수 없습니다.
 * 사용 예:
 * ```kotlin
 * purchaseCommands.start(
 *     StartPurchase(
 *         attemptId = attemptId,
 *         authorizationOperationId = operationId,
 *         idempotencyOwnerId = receiptOwner,
 *         buyerSubjectId = buyerId,
 *         ipSubjectId = ipId,
 *         grade = "VIP",
 *         quantity = 1,
 *         grant = admissionGrant,
 *         policy = salePolicy,
 *     ),
 * )
 * ```
 * @property attemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property authorizationOperationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property idempotencyOwnerId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property buyerSubjectId 호출 주체를 나타내는 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보나 비밀값은 저장·로그에 남기지 않습니다.
 * @property ipSubjectId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property grade 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property quantity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property grant 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property policy 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class StartPurchase(
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val attemptId: UUID,
    /**
     * `authorizationOperationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val authorizationOperationId: UUID,
    /**
     * `idempotencyOwnerId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val idempotencyOwnerId: Long,
    /**
     * `buyerSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val buyerSubjectId: UUID,
    /**
     * `ipSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val ipSubjectId: UUID,
    /**
     * `grade`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val grade: String,
    /**
     * `quantity`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val quantity: Int,
    /**
     * `grant`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val grant: ConsumeGrant,
    /**
     * `policy`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val policy: SalePolicySnapshot,
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
 * 아직 provider outcome을 가정하지 않고 owner-scoped cancellation을 요청합니다.
 * 이미 `APPROVED`인 구매를 임의로 취소하는 DTO가 아니며, service가 현재 state와
 * buyer subject를 확인한 뒤 payment cancellation workflow를 시작합니다.
 * @property attemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property buyerSubjectId 호출 주체를 나타내는 참조값입니다. 권한·중복 요청·감사 추적에 사용하며 원문 개인정보나 비밀값은 저장·로그에 남기지 않습니다.
 */
data class CancelPurchase(
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val attemptId: UUID,
    /**
     * `buyerSubjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val buyerSubjectId: UUID,
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
 * operation의 현재 claim owner만 provider outcome 하나를 적용하도록 하는 command입니다.
 * `claimToken`과 `claimRevision`은 늦게 도착한 worker가 최신 결과를 덮어쓰지 못하게
 * 하는 fencing 정보입니다. 같은 outcome을 재전달해도 idempotent하며, stale claim은
 * [ApplyResult.STALE]로 반환됩니다.
 * @property attemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property operationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property claimToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property claimRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property outcome 현재 콘서트 티켓 판매 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 */
data class ApplyPaymentOutcome(
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val attemptId: UUID,
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val operationId: UUID,
    /**
     * `claimToken`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val claimToken: UUID,
    /**
     * `claimRevision`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val claimRevision: Long,
    /**
     * `outcome`은 콘서트 티켓 플래시 세일에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val outcome: PaymentOutcome,
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
 * 외부 ticket 발급/회수 effect의 deduplicate된 결과를 purchase에 반영합니다.
 * `expectedRevision`이 일치할 때만 상태를 전이하고, `disposition`은 inventory를
 * 다시 풀어도 되는지에 대한 durable proof로 저장됩니다.
 * @property orderId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property operationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property claimToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property outcome 현재 콘서트 티켓 판매 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property disposition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */
data class ApplyTicketOutcome(
    /**
     * `orderId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val orderId: UUID,
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val operationId: UUID,
    /**
     * `claimToken`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val claimToken: UUID,
    /**
     * `expectedRevision`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `outcome`은 콘서트 티켓 플래시 세일에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val outcome: TicketEffectOutcome,
    /**
     * `disposition`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val disposition: TicketDisposition,
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
 * customer 또는 operations가 읽을 수 있는 owner-safe purchase projection입니다.
 * provider secret, payment credential, raw buyer/IP 값은 포함하지 않고 상태·revision·
 * 변경 시각만 제공합니다.
 * @property attemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 콘서트 티켓 판매 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property updatedAt 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */
data class PurchaseSnapshot(
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val attemptId: UUID,
    /**
     * `state`은 콘서트 티켓 플래시 세일에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: PurchaseState,
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
 * purchase module이 소유하는 command boundary입니다.
 *
 * 구현체는 각 command에서 PostgreSQL lock ordering, owner 검증, idempotency를
 * 적용해야 합니다. controller나 payment worker가 내부 repository를 직접 호출하지
 * 않도록 이 작은 API만 모듈 경계로 공개합니다.
 * 예: `start`의 HTTP response가 유실되어도 동일한 `StartPurchase`를 다시 호출하면
 * 새 inventory hold가 아니라 기존 [PurchaseSnapshot]을 반환해야 합니다.
 * ```kotlin
 * val first = purchaseCommands.start(command)
 * val replay = purchaseCommands.start(command) // same attempt, same snapshot
 * check(first.attemptId == replay.attemptId)
 * ```
 */
interface PurchaseCommands {
    /**
     * `start`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = start(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun start(command: StartPurchase): PurchaseSnapshot

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
    fun cancel(command: CancelPurchase): PurchaseSnapshot

    /**
     * `applyTicketOutcome`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = applyTicketOutcome(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun applyTicketOutcome(command: ApplyTicketOutcome): PurchaseSnapshot
}

/**
 * owner-scoped read boundary입니다. 다른 owner의 attempt는 존재하지 않는 attempt와
 * 구분하지 않아 enumeration을 막습니다.
 *
 * ```kotlin
 * val snapshot = purchaseQueries.owned(attemptId, buyerSubjectId)
 *     ?: throw NotFoundException("purchase not found")
 * ```
 */
fun interface PurchaseQueries {
    /**
     * `owned`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = owned(attemptId = Uuid.V7.nextId(), buyerSubjectId = Uuid.V7.nextId())
     * ```
     * @param attemptId 요청 경계에서 검증된 `attemptId`입니다.
     * @param buyerSubjectId 요청 경계에서 검증된 `buyerSubjectId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun owned(attemptId: UUID, buyerSubjectId: UUID): PurchaseSnapshot?
}

/**
 * fenced asynchronous outcome을 적용한 결과입니다.
 *
 * `APPLIED`는 현재 claim이 상태를 변경했다는 뜻이고, `STALE`은 이미 다른 worker가
 * 더 최신 claim을 처리했거나 operation이 terminal 상태라는 뜻입니다. `STALE`을
 * 오류로 재시도하면 같은 provider effect를 반복할 수 있으므로 caller는 먼저
 * [PurchaseQueries] 또는 [PaymentQueries]로 현재 상태를 읽어야 합니다.
 */
enum class ApplyResult {
    /** 현재 claim과 revision이 유효해 상태를 한 번 적용했습니다. */
    /** 현재 claim과 revision이 유효해 상태를 한 번 적용했습니다. */
    APPLIED,

    /** 오래된 claim이므로 상태를 바꾸지 않았습니다. */
    /** 오래된 claim이므로 상태를 바꾸지 않았습니다. */
    STALE
}

/**
 * payment module에서만 사용하는 fenced payment-result boundary입니다.
 * provider callback을 controller가 직접 purchase row에 쓰지 않고, operation claim을
 * 가진 worker가 이 API를 호출하게 해 늦은 승인·중복 callback을 안전하게 흡수합니다.
 *
 * ```kotlin
 * val result = paymentOutcomeCommands.applyPaymentOutcome(callbackCommand)
 * if (result == ApplyResult.STALE) paymentQueries.operation(operationId)
 * ```
 */
fun interface PaymentOutcomeCommands {
    /**
     * `applyPaymentOutcome`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = applyPaymentOutcome(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun applyPaymentOutcome(command: ApplyPaymentOutcome): ApplyResult
}

/**
 * payment authorization을 위한 안정적인 after-commit request입니다.
 * transaction commit 전에 외부 provider를 호출하지 않도록 event ID와 operation ID만
 * durable publication에 싣습니다. 재전달은 event ID로 deduplicate합니다.
 * @property eventId 이벤트의 불변 식별자입니다. inbox/outbox 중복 제거와 감사 추적의 기준이므로 재시도에도 같은 값을 유지합니다.
 * @property attemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property operationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */
data class AuthorizationRequested(
    /**
     * `eventId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
    /**
     * `attemptId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val attemptId: UUID,
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val operationId: UUID,
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
 * ticket issue/revoke effect를 위한 안정적인 after-commit request입니다.
 * `disposition`은 effect가 실패하거나 재시도되어도 inventory를 언제 반환할 수
 * 있는지 결정하는 business fact입니다.
 * @property eventId 이벤트의 불변 식별자입니다. inbox/outbox 중복 제거와 감사 추적의 기준이므로 재시도에도 같은 값을 유지합니다.
 * @property orderId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property operationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property disposition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */
data class TicketEffectRequested(
    /**
     * `eventId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
    /**
     * `orderId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val orderId: UUID,
    /**
     * `operationId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val operationId: UUID,
    /**
     * `disposition`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val disposition: TicketDisposition,
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
