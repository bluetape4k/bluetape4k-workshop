package io.bluetape4k.workshop.commerce.ticket.purchase.api

import io.bluetape4k.workshop.commerce.ticket.admission.api.ConsumeGrant
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.domain.TicketDisposition
import io.bluetape4k.workshop.commerce.ticket.domain.TicketEffectOutcome
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePolicySnapshot
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** Starts one owner-scoped purchase under the referenced sale policy. */
data class StartPurchase(
    val attemptId: UUID,
    val authorizationOperationId: UUID,
    val idempotencyOwnerId: Long,
    val buyerSubjectId: UUID,
    val ipSubjectId: UUID,
    val grade: String,
    val quantity: Int,
    val grant: ConsumeGrant,
    val policy: SalePolicySnapshot,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Requests an owner-scoped cancellation without assuming the provider outcome. */
data class CancelPurchase(
    val attemptId: UUID,
    val buyerSubjectId: UUID,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Applies one fenced provider outcome to a purchase. */
data class ApplyPaymentOutcome(
    val attemptId: UUID,
    val operationId: UUID,
    val claimToken: UUID,
    val claimRevision: Long,
    val outcome: PaymentOutcome,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Applies one deduplicated ticket effect outcome. */
data class ApplyTicketOutcome(
    val orderId: UUID,
    val operationId: UUID,
    val expectedRevision: Long,
    val outcome: TicketEffectOutcome,
    val disposition: TicketDisposition,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Owner-safe purchase projection. */
data class PurchaseSnapshot(
    val attemptId: UUID,
    val state: PurchaseState,
    val revision: Long,
    val updatedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Commands owned by the purchase module. */
interface PurchaseCommands {
    fun start(command: StartPurchase): PurchaseSnapshot

    fun cancel(command: CancelPurchase): PurchaseSnapshot

    fun applyTicketOutcome(command: ApplyTicketOutcome): PurchaseSnapshot
}

enum class ApplyResult { APPLIED, STALE }

/** Fenced payment-result boundary used only by the payment module. */
fun interface PaymentOutcomeCommands {
    fun applyPaymentOutcome(command: ApplyPaymentOutcome): ApplyResult
}

/** Stable after-commit request for payment authorization. */
data class AuthorizationRequested(
    val eventId: UUID,
    val attemptId: UUID,
    val operationId: UUID,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Stable after-commit request for a ticket effect. */
data class TicketEffectRequested(
    val eventId: UUID,
    val orderId: UUID,
    val operationId: UUID,
    val disposition: TicketDisposition,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
