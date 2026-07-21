package io.bluetape4k.workshop.commerce.ticket.operations.api

import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** Bounded manual reconciliation request. */
data class OperatorReconcile(
    val commandId: UUID,
    val limit: Int,
    val requestedAt: Instant,
    val reason: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Sanitized result of one bounded recovery run. */
data class ReconcileSummary(
    val processed: Int,
    val converged: Int,
    val quarantined: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Operator command boundary. */
fun interface OperationsCommands {
    fun reconcile(command: OperatorReconcile): ReconcileSummary
}
