package io.bluetape4k.workshop.commerce.ticket.domain

import java.io.Serializable

/** Stable sale lifecycle codes persisted by PostgreSQL. */
enum class SaleState(val code: String) {
    DRAFT("draft"),
    SCHEDULED("scheduled"),
    OPEN("open"),
    SUSPENDED("suspended"),
    CLOSED("closed"),
}

/** Explicit operator or scheduler commands for the sale lifecycle. */
enum class SaleCommand { SCHEDULE, OPEN, SUSPEND, RESUME, CLOSE }

/** Stable purchase lifecycle codes persisted by PostgreSQL. */
enum class PurchaseState(val code: String) {
    INVENTORY_HELD("inventory_held"),
    PAYMENT_AUTHORIZING("payment_authorizing"),
    RECONCILIATION_REQUIRED("reconciliation_required"),
    CANCELLATION_REQUESTED("cancellation_requested"),
    APPROVED("approved"),
    DECLINED("declined"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
    REFUND_PENDING("refund_pending"),
    REFUNDED("refunded"),
    REFUND_QUARANTINED("refund_quarantined"),
}

/** Provider outcome interpreted only when the matching operation is fenced. */
enum class PaymentOutcome { APPROVED, DECLINED, UNKNOWN, CANCELLATION_REQUESTED, EXPIRED }

/** Durable proof of whether an issued ticket can permit inventory restock. */
enum class TicketDisposition(val code: String) {
    PENDING("pending"),
    NEVER_ISSUED("never_issued"),
    ISSUED("issued"),
    REVOKED("revoked"),
}

/** Stable external-ticket effect lifecycle. */
enum class TicketState(val code: String) {
    ISSUE_PENDING("issue_pending"),
    ISSUE_RETRY("issue_retry"),
    ISSUED("issued"),
    REVOKE_PENDING("revoke_pending"),
    REVOKED("revoked"),
    QUARANTINED("quarantined"),
}

/** Deterministic result of one external ticket effect. */
enum class TicketEffectOutcome { SUCCEEDED, RETRYABLE_FAILURE, RETRY_BUDGET_EXHAUSTED }

/** Inventory and guard effects that must be committed with a purchase transition. */
data class PurchaseTransition(
    val next: PurchaseState,
    val heldDelta: Int,
    val soldDelta: Int,
    val releaseGuards: Boolean,
    val ticketDisposition: TicketDisposition? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
