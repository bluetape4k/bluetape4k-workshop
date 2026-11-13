package io.bluetape4k.workshop.commerce.ticket.domain

import java.io.Serial
import java.io.Serializable
import java.time.Instant

/** suspended sale을 안전하게 resume할 수 있는지 판단하는 데 필요한 input입니다. */
data class SaleTransitionContext(
    val now: Instant,
    val opensAt: Instant,
    val closesAt: Instant,
    val invariantsHealthy: Boolean,
): Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** 현재 window에 대해 오래되었거나 안전하지 않은 sale command를 거부합니다. */
class InvalidSaleTransition(
    val state: SaleState,
    val command: SaleCommand,
): IllegalStateException("invalid_sale_transition:${state.code}:${command.name.lowercase()}") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** 예상 purchase state와 일치하지 않는 provider outcome을 거부합니다. */
class InvalidPurchaseTransition(
    val state: PurchaseState,
    val outcome: PaymentOutcome,
): IllegalStateException("invalid_purchase_transition:${state.code}:${outcome.name.lowercase()}") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** 현재 effect state와 일치하지 않는 external ticket outcome을 거부합니다. */
class InvalidTicketTransition(
    val state: TicketState,
    val outcome: TicketEffectOutcome,
): IllegalStateException("invalid_ticket_transition:${state.code}:${outcome.name.lowercase()}") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

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

private fun SaleTransitionContext.insideWindow(): Boolean =
    !now.isBefore(opensAt) &&
            now.isBefore(closesAt)

private fun invalidSale(
    state: SaleState,
    command: SaleCommand,
): Nothing = throw InvalidSaleTransition(state, command)
