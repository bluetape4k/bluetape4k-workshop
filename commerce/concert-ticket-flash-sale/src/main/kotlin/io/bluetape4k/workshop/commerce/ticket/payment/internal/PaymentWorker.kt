package io.bluetape4k.workshop.commerce.ticket.payment.internal

import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPaymentOperationEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPaymentOperations
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyPaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyResult
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PaymentOutcomeCommands
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.Duration
import java.util.UUID

data class PaymentClaim(
    val operationId: UUID,
    val attemptId: UUID,
    val claimToken: UUID,
    val revision: Long,
)

/** Fenced claims implemented on Bluetape4k [TicketExposedJdbcRepository]. */
class PaymentOperationClaimRepository(
    private val jdbc: TicketJdbcExecutor,
    private val clock: Clock,
) : TicketExposedJdbcRepository<TicketPaymentOperationEntity, Long>(TicketPaymentOperationEntity::class.java) {
    fun claim(operationId: UUID, claimTtl: Duration): PaymentClaim? = jdbc.transaction {
        val now = clock.instant()
        val token = UUID.randomUUID()
        val claimed = TicketPaymentOperations.update({
            (TicketPaymentOperations.operationId eq operationId) and
                (TicketPaymentOperations.operationKind eq "authorize") and
                (
                    (TicketPaymentOperations.status eq "pending") or
                        ((TicketPaymentOperations.status eq "unknown") and
                            (TicketPaymentOperations.nextReconcileAt lessEq now)) or
                        ((TicketPaymentOperations.status eq "claimed") and
                            (TicketPaymentOperations.claimUntil lessEq now))
                    )
        }) {
            it[status] = "claimed"
            it[claimToken] = token
            it[claimRevision] = claimRevision + 1
            it[claimUntil] = now.plus(claimTtl)
            it[updatedAt] = now
        }
        if (claimed != 1) return@transaction null
        TicketPaymentOperations.selectAll()
            .where {
                (TicketPaymentOperations.operationId eq operationId) and
                    (TicketPaymentOperations.claimToken eq token)
            }
            .singleOrNull()
            ?.let { PaymentClaim(operationId, it[TicketPaymentOperations.attemptId], token, it[TicketPaymentOperations.claimRevision]) }
    }
}

/** Claims briefly in PostgreSQL, calls the provider outside locks, then applies with fencing. */
class PaymentWorker(
    jdbc: TicketJdbcExecutor,
    private val outcomes: PaymentOutcomeCommands,
    private val provider: PaymentProvider,
    private val claimTtl: Duration = Duration.ofSeconds(5),
    clock: Clock = Clock.systemUTC(),
    private val claims: PaymentOperationClaimRepository = PaymentOperationClaimRepository(jdbc, clock),
) {
    fun claim(operationId: UUID): PaymentClaim? = claims.claim(operationId, claimTtl)

    fun apply(
        claim: PaymentClaim,
        outcome: PaymentOutcome,
    ): ApplyResult =
        outcomes.applyPaymentOutcome(
            ApplyPaymentOutcome(
                attemptId = claim.attemptId,
                operationId = claim.operationId,
                claimToken = claim.claimToken,
                claimRevision = claim.revision,
                outcome = outcome,
            ),
        )

    fun run(operationId: UUID): ApplyResult? {
        val claim = claim(operationId) ?: return null
        val outcome =
            provider.lookup(operationId) ?: try {
                provider.authorize(operationId)
            } catch (_: PaymentTimeout) {
                PaymentOutcome.UNKNOWN
            }
        return apply(claim, outcome)
    }
}
