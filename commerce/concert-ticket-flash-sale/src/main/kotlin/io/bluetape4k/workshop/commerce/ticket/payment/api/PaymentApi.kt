package io.bluetape4k.workshop.commerce.ticket.payment.api

import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** provider secret이 없는 operations-safe payment projection입니다. */
data class PaymentOperationSnapshot(
    val operationId: UUID,
    val outcome: PaymentOutcome?,
    val revision: Long,
    val nextReconcileAt: Instant?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** payment reconciliation state의 read boundary입니다. */
fun interface PaymentQueries {
    fun operation(operationId: UUID): PaymentOperationSnapshot?
}
