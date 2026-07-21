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

/** Applies one fenced provider outcome to a purchase. */
data class ApplyPaymentOutcome(
    val attemptId: UUID,
    val operationId: UUID,
    val expectedRevision: Long,
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

    fun applyPaymentOutcome(command: ApplyPaymentOutcome): PurchaseSnapshot

    fun applyTicketOutcome(command: ApplyTicketOutcome): PurchaseSnapshot
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
