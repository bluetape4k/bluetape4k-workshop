package io.bluetape4k.workshop.commerce.ticket.domain

import java.io.Serial
import java.io.Serializable
import java.time.Instant

/** Inputs required to decide whether a suspended sale can resume safely. */
data class SaleTransitionContext(
    val now: Instant,
    val opensAt: Instant,
    val closesAt: Instant,
    val invariantsHealthy: Boolean,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Rejects a sale command that is stale or unsafe for the current window. */
class InvalidSaleTransition(
    val state: SaleState,
    val command: SaleCommand,
) : IllegalStateException("invalid_sale_transition:${state.code}:${command.name.lowercase()}") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Rejects a provider outcome that does not match the expected purchase state. */
class InvalidPurchaseTransition(
    val state: PurchaseState,
    val outcome: PaymentOutcome,
) : IllegalStateException("invalid_purchase_transition:${state.code}:${outcome.name.lowercase()}") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Rejects an external ticket outcome that does not match the current effect state. */
class InvalidTicketTransition(
    val state: TicketState,
    val outcome: TicketEffectOutcome,
) : IllegalStateException("invalid_ticket_transition:${state.code}:${outcome.name.lowercase()}") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Evaluates the pure sale lifecycle without performing persistence. */
fun saleTransition(
    state: SaleState,
    command: SaleCommand,
    context: SaleTransitionContext,
): SaleState =
    when (state to command) {
        SaleState.DRAFT to SaleCommand.SCHEDULE -> SaleState.SCHEDULED
        SaleState.SCHEDULED to SaleCommand.OPEN ->
            if (context.insideWindow()) SaleState.OPEN else invalidSale(state, command)
        SaleState.SCHEDULED to SaleCommand.SUSPEND,
        SaleState.OPEN to SaleCommand.SUSPEND,
        -> SaleState.SUSPENDED
        SaleState.SUSPENDED to SaleCommand.RESUME ->
            if (context.insideWindow() && context.invariantsHealthy) SaleState.OPEN else invalidSale(state, command)
        SaleState.SCHEDULED to SaleCommand.CLOSE,
        SaleState.OPEN to SaleCommand.CLOSE,
        SaleState.SUSPENDED to SaleCommand.CLOSE,
        -> SaleState.CLOSED
        else -> invalidSale(state, command)
    }

/** Evaluates a fenced payment outcome and its atomic inventory delta. */
fun transition(
    state: PurchaseState,
    outcome: PaymentOutcome,
): PurchaseTransition =
    when (state to outcome) {
        PurchaseState.INVENTORY_HELD to PaymentOutcome.UNKNOWN,
        PurchaseState.PAYMENT_AUTHORIZING to PaymentOutcome.UNKNOWN ->
            PurchaseTransition(PurchaseState.RECONCILIATION_REQUIRED, 0, 0, false)
        PurchaseState.INVENTORY_HELD to PaymentOutcome.APPROVED,
        PurchaseState.PAYMENT_AUTHORIZING to PaymentOutcome.APPROVED,
        PurchaseState.RECONCILIATION_REQUIRED to PaymentOutcome.APPROVED,
        -> PurchaseTransition(PurchaseState.APPROVED, -1, 1, true, TicketDisposition.PENDING)
        PurchaseState.INVENTORY_HELD to PaymentOutcome.DECLINED,
        PurchaseState.PAYMENT_AUTHORIZING to PaymentOutcome.DECLINED,
        PurchaseState.RECONCILIATION_REQUIRED to PaymentOutcome.DECLINED,
        -> PurchaseTransition(PurchaseState.DECLINED, -1, 0, true)
        PurchaseState.PAYMENT_AUTHORIZING to PaymentOutcome.CANCELLATION_REQUESTED,
        PurchaseState.RECONCILIATION_REQUIRED to PaymentOutcome.CANCELLATION_REQUESTED,
        -> PurchaseTransition(PurchaseState.CANCELLATION_REQUESTED, 0, 0, false)
        PurchaseState.CANCELLATION_REQUESTED to PaymentOutcome.DECLINED ->
            PurchaseTransition(PurchaseState.CANCELLED, -1, 0, true)
        PurchaseState.CANCELLATION_REQUESTED to PaymentOutcome.APPROVED ->
            PurchaseTransition(PurchaseState.REFUND_PENDING, -1, 1, false, TicketDisposition.NEVER_ISSUED)
        PurchaseState.INVENTORY_HELD to PaymentOutcome.EXPIRED ->
            PurchaseTransition(PurchaseState.EXPIRED, -1, 0, true)
        else -> throw InvalidPurchaseTransition(state, outcome)
    }

/** Finalizes restock only when refund and ticket disposition both prove safety. */
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
        -> PurchaseTransition(PurchaseState.REFUNDED, 0, -1, true, disposition)
        TicketDisposition.PENDING,
        TicketDisposition.ISSUED,
        -> PurchaseTransition(PurchaseState.REFUND_PENDING, 0, 0, false, disposition)
    }
}

/** Evaluates the pure issue/revoke effect lifecycle. */
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
        TicketState.REVOKE_PENDING to TicketEffectOutcome.SUCCEEDED -> TicketState.REVOKED
        TicketState.REVOKE_PENDING to TicketEffectOutcome.RETRYABLE_FAILURE -> TicketState.REVOKE_PENDING
        TicketState.REVOKE_PENDING to TicketEffectOutcome.RETRY_BUDGET_EXHAUSTED -> TicketState.QUARANTINED
        else -> throw InvalidTicketTransition(state, outcome)
    }

private fun SaleTransitionContext.insideWindow(): Boolean = !now.isBefore(opensAt) && now.isBefore(closesAt)

private fun invalidSale(
    state: SaleState,
    command: SaleCommand,
): Nothing = throw InvalidSaleTransition(state, command)
