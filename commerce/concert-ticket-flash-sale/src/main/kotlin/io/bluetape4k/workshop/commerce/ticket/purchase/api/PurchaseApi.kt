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

/** 참조한 sale policy 아래에서 owner-scoped purchase 하나를 시작합니다. */
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

/** provider outcome을 가정하지 않고 owner-scoped cancellation을 요청합니다. */
data class CancelPurchase(
    val attemptId: UUID,
    val buyerSubjectId: UUID,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** fenced provider outcome 하나를 purchase에 적용합니다. */
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

/** deduplicate된 ticket effect outcome 하나를 적용합니다. */
data class ApplyTicketOutcome(
    val orderId: UUID,
    val operationId: UUID,
    val claimToken: UUID,
    val expectedRevision: Long,
    val outcome: TicketEffectOutcome,
    val disposition: TicketDisposition,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** owner-safe purchase projection입니다. */
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

/** purchase module이 소유하는 command입니다. */
interface PurchaseCommands {
    fun start(command: StartPurchase): PurchaseSnapshot

    fun cancel(command: CancelPurchase): PurchaseSnapshot

    fun applyTicketOutcome(command: ApplyTicketOutcome): PurchaseSnapshot
}

/** owner-scoped read boundary입니다. 다른 owner의 시도는 존재하지 않는 시도와 구분하지 않습니다. */
fun interface PurchaseQueries {
    fun owned(attemptId: UUID, buyerSubjectId: UUID): PurchaseSnapshot?
}

enum class ApplyResult { APPLIED, STALE }

/** payment module에서만 사용하는 fenced payment-result boundary입니다. */
fun interface PaymentOutcomeCommands {
    fun applyPaymentOutcome(command: ApplyPaymentOutcome): ApplyResult
}

/** payment authorization을 위한 안정적인 after-commit request입니다. */
data class AuthorizationRequested(
    val eventId: UUID,
    val attemptId: UUID,
    val operationId: UUID,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** ticket effect를 위한 안정적인 after-commit request입니다. */
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
