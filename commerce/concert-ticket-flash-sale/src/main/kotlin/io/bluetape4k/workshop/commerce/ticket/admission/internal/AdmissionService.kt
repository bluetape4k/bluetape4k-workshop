package io.bluetape4k.workshop.commerce.ticket.admission.internal

import io.bluetape4k.workshop.commerce.ticket.admission.api.AdmissionCommands
import io.bluetape4k.workshop.commerce.ticket.admission.api.ConsumeGrant
import io.bluetape4k.workshop.commerce.ticket.admission.api.TransactionalAdmissionCommands
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import java.io.Serial
import java.time.Clock
import java.time.ZoneOffset

/** A grant was expired, mismatched, or already consumed. */
class AdmissionExpired : IllegalStateException("admission_expired") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Consumes a buyer-bound grant with one conditional PostgreSQL update. */
class AdmissionService(
    private val jdbc: TicketJdbcExecutor,
    private val clock: Clock,
) : AdmissionCommands, TransactionalAdmissionCommands {
    override fun consume(command: ConsumeGrant) {
        jdbc.transaction { consume(this, command) }
    }

    override fun consume(
        transaction: TicketJdbcTransaction,
        command: ConsumeGrant,
    ) {
        with(transaction) {
            connection.prepareStatement(
                """
                UPDATE ticket_admission_grants
                SET consumed_attempt_id = ?, consumed_at = ?,
                    expires_at = expires_at
                WHERE sale_id = ? AND grant_nonce = ? AND buyer_subject_id = ?
                  AND policy_version = ? AND consumed_attempt_id IS NULL AND expires_at > ?
                """.trimIndent(),
            ).use { statement ->
                val now = clock.instant()
                statement.setObject(1, command.attemptId)
                statement.setObject(2, now.atOffset(ZoneOffset.UTC))
                statement.setObject(3, command.saleId)
                statement.setObject(4, command.grantNonce)
                statement.setObject(5, command.buyerSubjectId)
                statement.setLong(6, command.policyVersion)
                statement.setObject(7, now.atOffset(ZoneOffset.UTC))
                if (statement.executeUpdate() != 1) throw AdmissionExpired()
            }
        }
    }
}
