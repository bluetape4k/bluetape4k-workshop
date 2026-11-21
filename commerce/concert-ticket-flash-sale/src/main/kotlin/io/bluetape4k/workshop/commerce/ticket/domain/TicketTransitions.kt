package io.bluetape4k.workshop.commerce.ticket.domain

import java.io.Serial
import java.io.Serializable
import java.time.Instant

/** suspended sale을 안전하게 resume할 수 있는지 판단하는 데 필요한 input입니다. */
/** suspended sale을 안전하게 resume할 수 있는지 판단하는 데 필요한 input입니다. */
data class SaleTransitionContext(
    /**
     * `now`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val now: Instant,
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
    /**
     * `invariantsHealthy`은 콘서트 티켓 플래시 세일에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val invariantsHealthy: Boolean,
): Serializable {
    companion object {
        @Serial
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/** 현재 window에 대해 오래되었거나 안전하지 않은 sale command를 거부합니다. */
/** 현재 window에 대해 오래되었거나 안전하지 않은 sale command를 거부합니다. */
class InvalidSaleTransition(
    /**
     * `state`은 콘서트 티켓 플래시 세일에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: SaleState,
    /**
     * `command`은 콘서트 티켓 플래시 세일에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val command: SaleCommand,
): IllegalStateException("invalid_sale_transition:${state.code}:${command.name.lowercase()}") {
    companion object {
        @Serial
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/** 예상 purchase state와 일치하지 않는 provider outcome을 거부합니다. */
/** 예상 purchase state와 일치하지 않는 provider outcome을 거부합니다. */
class InvalidPurchaseTransition(
    /**
     * `state`은 콘서트 티켓 플래시 세일에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: PurchaseState,
    /**
     * `outcome`은 콘서트 티켓 플래시 세일에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val outcome: PaymentOutcome,
): IllegalStateException("invalid_purchase_transition:${state.code}:${outcome.name.lowercase()}") {
    companion object {
        @Serial
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/** 현재 effect state와 일치하지 않는 external ticket outcome을 거부합니다. */
/** 현재 effect state와 일치하지 않는 external ticket outcome을 거부합니다. */
class InvalidTicketTransition(
    /**
     * `state`은 콘서트 티켓 플래시 세일에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: TicketState,
    /**
     * `outcome`은 콘서트 티켓 플래시 세일에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val outcome: TicketEffectOutcome,
): IllegalStateException("invalid_ticket_transition:${state.code}:${outcome.name.lowercase()}") {
    companion object {
        @Serial
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/** persistence를 수행하지 않고 순수 sale lifecycle을 평가합니다. */
/** persistence를 수행하지 않고 순수 sale lifecycle을 평가합니다. */
fun saleTransition(
    state: SaleState,
    command: SaleCommand,
    context: SaleTransitionContext,
): SaleState =
    when (state to command) {
        SaleState.DRAFT to SaleCommand.SCHEDULE   -> SaleState.SCHEDULED

        SaleState.SCHEDULED to SaleCommand.OPEN   ->
            if (context.insideWindow()) SaleState.OPEN
            else invalidSale(state, command)

        SaleState.SCHEDULED to SaleCommand.SUSPEND,
        SaleState.OPEN to SaleCommand.SUSPEND,
                                                  -> SaleState.SUSPENDED
        SaleState.SUSPENDED to SaleCommand.RESUME ->
            if (context.insideWindow() && context.invariantsHealthy) SaleState.OPEN
            else invalidSale(state, command)

        SaleState.SCHEDULED to SaleCommand.CLOSE,
        SaleState.OPEN to SaleCommand.CLOSE,
        SaleState.SUSPENDED to SaleCommand.CLOSE,
                                                  -> SaleState.CLOSED

        else                                      -> invalidSale(state, command)
    }

/** fenced payment outcome과 해당 atomic inventory delta를 평가합니다. */
/** fenced payment outcome과 해당 atomic inventory delta를 평가합니다. */
fun transition(
    state: PurchaseState,
    outcome: PaymentOutcome,
): PurchaseTransition =
    when (state to outcome) {
        PurchaseState.INVENTORY_HELD to PaymentOutcome.UNKNOWN,
        PurchaseState.PAYMENT_AUTHORIZING to PaymentOutcome.UNKNOWN,
                                                                        ->
            PurchaseTransition(
                next = PurchaseState.RECONCILIATION_REQUIRED,
                heldDelta = 0,
                soldDelta = 0,
                releaseGuards = false
            )
        PurchaseState.INVENTORY_HELD to PaymentOutcome.APPROVED,
        PurchaseState.PAYMENT_AUTHORIZING to PaymentOutcome.APPROVED,
        PurchaseState.RECONCILIATION_REQUIRED to PaymentOutcome.APPROVED,
                                                                        ->
            PurchaseTransition(
                next = PurchaseState.APPROVED,
                heldDelta = -1,
                soldDelta = 1,
                releaseGuards = true,
                ticketDisposition = TicketDisposition.PENDING
            )
        PurchaseState.INVENTORY_HELD to PaymentOutcome.DECLINED,
        PurchaseState.PAYMENT_AUTHORIZING to PaymentOutcome.DECLINED,
        PurchaseState.RECONCILIATION_REQUIRED to PaymentOutcome.DECLINED,
                                                                        ->
            PurchaseTransition(
                next = PurchaseState.DECLINED,
                heldDelta = -1,
                soldDelta = 0,
                releaseGuards = true
            )
        PurchaseState.PAYMENT_AUTHORIZING to PaymentOutcome.CANCELLATION_REQUESTED,
        PurchaseState.RECONCILIATION_REQUIRED to PaymentOutcome.CANCELLATION_REQUESTED,
                                                                        ->
            PurchaseTransition(
                next = PurchaseState.CANCELLATION_REQUESTED,
                heldDelta = 0,
                soldDelta = 0,
                releaseGuards = false
            )
        PurchaseState.CANCELLATION_REQUESTED to PaymentOutcome.DECLINED ->
            PurchaseTransition(
                next = PurchaseState.CANCELLED,
                heldDelta = -1,
                soldDelta = 0,
                releaseGuards = true
            )
        PurchaseState.CANCELLATION_REQUESTED to PaymentOutcome.APPROVED ->
            PurchaseTransition(
                next = PurchaseState.REFUND_PENDING,
                heldDelta = -1,
                soldDelta = 1,
                releaseGuards = false,
                ticketDisposition = TicketDisposition.NEVER_ISSUED
            )
        PurchaseState.INVENTORY_HELD to PaymentOutcome.EXPIRED          ->
            PurchaseTransition(
                next = PurchaseState.EXPIRED,
                heldDelta = -1,
                soldDelta = 0,
                releaseGuards = true
            )
        else                                                            ->
            throw InvalidPurchaseTransition(state, outcome)
    }

/**
 * refundTransition은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
fun refundTransition(
    state: PurchaseState,
    disposition: TicketDisposition,
): PurchaseTransition {
    if (state != PurchaseState.REFUND_PENDING) {
        throw InvalidPurchaseTransition(state, PaymentOutcome.UNKNOWN)
    }
    return when (disposition) {
        TicketDisposition.NEVER_ISSUED,
        TicketDisposition.REVOKED,
            -> PurchaseTransition(
            next = PurchaseState.REFUNDED,
            heldDelta = 0,
            soldDelta = -1,
            releaseGuards = true,
            ticketDisposition = disposition
        )

        TicketDisposition.PENDING,
        TicketDisposition.ISSUED,
            -> PurchaseTransition(
            next = PurchaseState.REFUND_PENDING,
            heldDelta = 0,
            soldDelta = 0,
            releaseGuards = false,
            ticketDisposition = disposition
        )
    }
}

/** 순수 issue/revoke effect lifecycle을 평가합니다. */
/** 순수 issue/revoke effect lifecycle을 평가합니다. */
fun ticketTransition(
    state: TicketState,
    outcome: TicketEffectOutcome,
): TicketState =
    when (state to outcome) {
        TicketState.ISSUE_PENDING to TicketEffectOutcome.SUCCEEDED,
        TicketState.ISSUE_RETRY to TicketEffectOutcome.SUCCEEDED,
                                                                            -> TicketState.ISSUED

        TicketState.ISSUE_PENDING to TicketEffectOutcome.RETRYABLE_FAILURE,
        TicketState.ISSUE_RETRY to TicketEffectOutcome.RETRYABLE_FAILURE,
                                                                            -> TicketState.ISSUE_RETRY

        TicketState.REVOKE_PENDING to TicketEffectOutcome.SUCCEEDED         -> TicketState.REVOKED
        TicketState.REVOKE_PENDING to TicketEffectOutcome.RETRYABLE_FAILURE -> TicketState.REVOKE_PENDING
        TicketState.REVOKE_PENDING to TicketEffectOutcome.RETRY_BUDGET_EXHAUSTED -> TicketState.QUARANTINED

        else                                                                ->
            throw InvalidTicketTransition(state, outcome)
    }

/**
 * `insideWindow`은 콘서트 티켓 플래시 세일 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
private fun SaleTransitionContext.insideWindow(): Boolean =
    !now.isBefore(opensAt) &&
            now.isBefore(closesAt)

/**
 * invalidSale은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
private fun invalidSale(
    state: SaleState,
    command: SaleCommand,
): Nothing = throw InvalidSaleTransition(state, command)
