package io.bluetape4k.workshop.commerce.ticket.operations.api

import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** 한계가 정해진 manual reconciliation request입니다. */
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

/** 단일 bounded recovery 실행의 sanitize된 결과입니다. */
data class ReconcileSummary(
    val processed: Int,
    val converged: Int,
    val quarantined: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** operator command boundary입니다. */
fun interface OperationsCommands {
    fun reconcile(command: OperatorReconcile): ReconcileSummary
}
