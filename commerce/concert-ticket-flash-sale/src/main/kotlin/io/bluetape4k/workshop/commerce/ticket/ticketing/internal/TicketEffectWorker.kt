package io.bluetape4k.workshop.commerce.ticket.ticketing.internal

import io.bluetape4k.workshop.commerce.ticket.domain.TicketDisposition
import io.bluetape4k.workshop.commerce.ticket.domain.TicketEffectOutcome
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketEffectOperationEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketEffectOperations
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyTicketOutcome
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseCommands
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun interface TicketProvider {
    fun issue(operationId: UUID): TicketEffectOutcome

    fun lookup(operationId: UUID): TicketEffectOutcome? = null
}

class TicketResponseLost : IllegalStateException("ticket_response_lost")

class FakeTicketProvider : TicketProvider {
    private val outcomes = ConcurrentHashMap<UUID, TicketEffectOutcome>()
    private val calls = ConcurrentHashMap<UUID, AtomicInteger>()
    private val responseLosses = ConcurrentHashMap.newKeySet<UUID>()

    override fun issue(operationId: UUID): TicketEffectOutcome {
        calls.computeIfAbsent(operationId) { AtomicInteger() }.incrementAndGet()
        val outcome = outcomes.computeIfAbsent(operationId) { TicketEffectOutcome.SUCCEEDED }
        if (responseLosses.remove(operationId)) throw TicketResponseLost()
        return outcome
    }

    override fun lookup(operationId: UUID): TicketEffectOutcome? = outcomes[operationId]

    fun succeedButLoseResponse(operationId: UUID) {
        responseLosses += operationId
    }

    fun issueCount(operationId: UUID): Int = calls[operationId]?.get() ?: 0
}

data class TicketEffectClaim(
    val operationId: UUID,
    val orderId: UUID,
    val token: UUID,
    val revision: Long,
)

class TicketEffectRepository(
    private val jdbc: TicketJdbcExecutor,
    private val clock: Clock,
) : TicketExposedJdbcRepository<TicketEffectOperationEntity, Long>(TicketEffectOperationEntity::class.java) {
    fun claim(operationId: UUID, ttl: Duration): TicketEffectClaim? = jdbc.transaction {
        val now = clock.instant()
        val token = UUID.randomUUID()
        val claimed = TicketEffectOperations.update({
            (TicketEffectOperations.operationId eq operationId) and
                (TicketEffectOperations.effectKind eq "issue") and
                ((TicketEffectOperations.status eq "pending") or
                    (TicketEffectOperations.status eq "retry") or
                    ((TicketEffectOperations.status eq "claimed") and (TicketEffectOperations.claimUntil lessEq now)))
        }) {
            it[status] = "claimed"
            it[claimToken] = token
            it[claimRevision] = claimRevision + 1
            it[claimUntil] = now.plus(ttl)
            it[updatedAt] = now
        }
        if (claimed != 1) return@transaction null
        TicketEffectOperations.selectAll().where {
            (TicketEffectOperations.operationId eq operationId) and (TicketEffectOperations.claimToken eq token)
        }.single().let {
            TicketEffectClaim(operationId, it[TicketEffectOperations.orderId], token, it[TicketEffectOperations.claimRevision])
        }
    }
}

class TicketEffectWorker(
    jdbc: TicketJdbcExecutor,
    private val purchases: PurchaseCommands,
    private val provider: TicketProvider,
    clock: Clock = Clock.systemUTC(),
    private val claimTtl: Duration = Duration.ofSeconds(5),
    private val effects: TicketEffectRepository = TicketEffectRepository(jdbc, clock),
) {
    fun run(operationId: UUID): Boolean {
        val claim = effects.claim(operationId, claimTtl) ?: return false
        val outcome = provider.lookup(operationId) ?: try {
            provider.issue(operationId)
        } catch (_: TicketResponseLost) {
            TicketEffectOutcome.RETRYABLE_FAILURE
        }
        purchases.applyTicketOutcome(
            ApplyTicketOutcome(
                orderId = claim.orderId,
                operationId = claim.operationId,
                claimToken = claim.token,
                expectedRevision = claim.revision,
                outcome = outcome,
                disposition = TicketDisposition.ISSUED,
            ),
        )
        return true
    }
}
