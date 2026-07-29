package io.bluetape4k.workshop.commerce.ticket.admission.api

import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import java.io.Serializable
import java.util.UUID

/** purchase transaction 안에서 소비되는 1회용 waiting-room grant입니다. */
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

/** transaction을 보장하는 admission boundary입니다. */
fun interface AdmissionCommands {
    fun consume(command: ConsumeGrant)
}

/** admission 구현을 노출하지 않고 internal transaction 참여 지점을 제공합니다. */
fun interface TransactionalAdmissionCommands {
    fun consume(
        transaction: TicketJdbcTransaction,
        command: ConsumeGrant,
    )
}
