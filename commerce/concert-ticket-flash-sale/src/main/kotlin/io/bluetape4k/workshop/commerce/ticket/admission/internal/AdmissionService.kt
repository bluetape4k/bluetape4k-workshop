package io.bluetape4k.workshop.commerce.ticket.admission.internal

import io.bluetape4k.workshop.commerce.ticket.admission.api.AdmissionCommands
import io.bluetape4k.workshop.commerce.ticket.admission.api.ConsumeGrant
import io.bluetape4k.workshop.commerce.ticket.admission.api.TransactionalAdmissionCommands
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketAdmissionGrantEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketAdmissionGrants
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serial
import java.time.Clock

class AdmissionExpired : IllegalStateException("admission_expired") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Admission authority implemented as a Bluetape4k Exposed JDBC repository. */
class TicketAdmissionGrantRepository :
    TicketExposedJdbcRepository<TicketAdmissionGrantEntity, Long>(TicketAdmissionGrantEntity::class.java) {
    fun consume(transaction: TicketJdbcTransaction, command: ConsumeGrant, now: java.time.Instant) {
        with(transaction) {
            val updated = TicketAdmissionGrants.update({
                (TicketAdmissionGrants.saleId eq command.saleId) and
                    (TicketAdmissionGrants.grantNonce eq command.grantNonce) and
                    (TicketAdmissionGrants.buyerSubjectId eq command.buyerSubjectId) and
                    (TicketAdmissionGrants.policyVersion eq command.policyVersion) and
                    TicketAdmissionGrants.consumedAttemptId.isNull() and
                    (TicketAdmissionGrants.expiresAt greater now)
            }) {
                it[consumedAttemptId] = command.attemptId
                it[consumedAt] = now
            }
            if (updated != 1) throw AdmissionExpired()
        }
    }
}

/** Consumes a buyer-bound grant with one conditional Exposed update. */
class AdmissionService(
    private val jdbc: TicketJdbcExecutor,
    private val clock: Clock,
    private val grants: TicketAdmissionGrantRepository = TicketAdmissionGrantRepository(),
) : AdmissionCommands, TransactionalAdmissionCommands {
    override fun consume(command: ConsumeGrant) {
        jdbc.transaction { consume(this, command) }
    }

    override fun consume(transaction: TicketJdbcTransaction, command: ConsumeGrant) {
        grants.consume(transaction, command, clock.instant())
    }
}
