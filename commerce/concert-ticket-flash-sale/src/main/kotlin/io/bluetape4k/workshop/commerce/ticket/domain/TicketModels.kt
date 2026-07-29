package io.bluetape4k.workshop.commerce.ticket.domain

import java.io.Serializable

/** PostgreSQL에 저장되는 안정적인 sale lifecycle code입니다. */
enum class SaleState(val code: String) {
    DRAFT("draft"),
    SCHEDULED("scheduled"),
    OPEN("open"),
    SUSPENDED("suspended"),
    CLOSED("closed"),
}

/** sale lifecycle을 위한 명시적인 operator 또는 scheduler command입니다. */
enum class SaleCommand { SCHEDULE, OPEN, SUSPEND, RESUME, CLOSE }

/** PostgreSQL에 저장되는 안정적인 purchase lifecycle code입니다. */
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

/** 일치하는 operation이 fenced 상태일 때만 해석되는 provider outcome입니다. */
enum class PaymentOutcome { APPROVED, DECLINED, UNKNOWN, CANCELLATION_REQUESTED, EXPIRED }

/** 발급된 ticket이 inventory restock을 허용할 수 있는지에 대한 durable proof입니다. */
enum class TicketDisposition(val code: String) {
    PENDING("pending"),
    NEVER_ISSUED("never_issued"),
    ISSUED("issued"),
    REVOKED("revoked"),
}

/** 안정적인 external-ticket effect lifecycle입니다. */
enum class TicketState(val code: String) {
    ISSUE_PENDING("issue_pending"),
    ISSUE_RETRY("issue_retry"),
    ISSUED("issued"),
    REVOKE_PENDING("revoke_pending"),
    REVOKED("revoked"),
    QUARANTINED("quarantined"),
}

/** 단일 external ticket effect의 결정적 결과입니다. */
enum class TicketEffectOutcome { SUCCEEDED, RETRYABLE_FAILURE, RETRY_BUDGET_EXHAUSTED }

/** purchase transition과 함께 commit해야 하는 inventory 및 guard effect입니다. */
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
