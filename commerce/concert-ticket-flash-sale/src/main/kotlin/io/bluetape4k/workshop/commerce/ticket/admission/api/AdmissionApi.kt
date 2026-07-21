package io.bluetape4k.workshop.commerce.ticket.admission.api

import java.io.Serializable
import java.util.UUID

/** Single-use waiting-room grant consumed inside the purchase transaction. */
data class ConsumeGrant(
    val saleId: UUID,
    val grantNonce: UUID,
    val buyerSubjectId: UUID,
    val policyVersion: Long,
    val attemptId: UUID,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Transactional admission boundary. */
fun interface AdmissionCommands {
    fun consume(command: ConsumeGrant)
}
