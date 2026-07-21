package io.bluetape4k.workshop.commerce.ticket.payment.api

import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** Operations-safe payment projection without provider secrets. */
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

/** Read boundary for payment reconciliation state. */
fun interface PaymentQueries {
    fun operation(operationId: UUID): PaymentOperationSnapshot?
}
