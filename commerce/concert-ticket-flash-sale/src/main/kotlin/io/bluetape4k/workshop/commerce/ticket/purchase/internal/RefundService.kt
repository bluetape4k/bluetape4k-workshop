package io.bluetape4k.workshop.commerce.ticket.purchase.internal

import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.domain.TicketDisposition
import io.bluetape4k.workshop.commerce.ticket.domain.refundTransition
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketActiveIdentityGuards
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketBuyerSaleStates
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketEffectReceipts
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketInventories
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketOrderEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketOrders
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPaymentOperationEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPaymentOperations
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPurchaseAttempts
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serial
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class RefundOutcome { SUCCEEDED, UNKNOWN, FAILED }

fun interface RefundProvider {
    fun refund(operationId: UUID): RefundOutcome

    fun lookup(operationId: UUID): RefundOutcome? = null
}

class FakeRefundProvider : RefundProvider {
    private val outcomes = ConcurrentHashMap<UUID, RefundOutcome>()
    private val configured = ConcurrentHashMap<UUID, RefundOutcome>()
    private val calls = ConcurrentHashMap<UUID, AtomicInteger>()

    override fun refund(operationId: UUID): RefundOutcome {
        calls.computeIfAbsent(operationId) { AtomicInteger() }.incrementAndGet()
        return outcomes.computeIfAbsent(operationId) { configured[operationId] ?: RefundOutcome.SUCCEEDED }
    }

    override fun lookup(operationId: UUID): RefundOutcome? = outcomes[operationId]

    fun succeed(operationId: UUID) {
        configured[operationId] = RefundOutcome.SUCCEEDED
    }

    fun refundCount(operationId: UUID): Int = calls[operationId]?.get() ?: 0
}

data class RefundClaim(
    val operationId: UUID,
    val orderId: UUID,
    val token: UUID,
    val revision: Long,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

class RefundOperationRepository(
    private val jdbc: TicketJdbcExecutor,
    private val clock: Clock,
) : TicketExposedJdbcRepository<TicketPaymentOperationEntity, Long>(TicketPaymentOperationEntity::class.java) {
    fun claim(operationId: UUID, ttl: Duration): RefundClaim? = jdbc.transaction {
        val now = clock.instant()
        val token = Uuid.V7.nextId()
        val claimed = TicketPaymentOperations.update({
            (TicketPaymentOperations.operationId eq operationId) and
                (TicketPaymentOperations.operationKind eq "refund") and
                ((TicketPaymentOperations.status eq "pending") or
                    ((TicketPaymentOperations.status eq "unknown") and (TicketPaymentOperations.nextReconcileAt lessEq now)) or
                    ((TicketPaymentOperations.status eq "claimed") and (TicketPaymentOperations.claimUntil lessEq now)))
        }) {
            it[status] = "claimed"
            it[claimToken] = token
            it[claimRevision] = claimRevision + 1
            it[claimUntil] = now.plus(ttl)
            it[updatedAt] = now
        }
        if (claimed != 1) return@transaction null
        TicketPaymentOperations.selectAll().where {
            (TicketPaymentOperations.operationId eq operationId) and (TicketPaymentOperations.claimToken eq token)
        }.single().let {
            RefundClaim(operationId, checkNotNull(it[TicketPaymentOperations.orderId]), token, it[TicketPaymentOperations.claimRevision])
        }
    }
}

class TicketRefundRepository(
    private val jdbc: TicketJdbcExecutor,
    private val clock: Clock,
) : TicketExposedJdbcRepository<TicketOrderEntity, UUID>(TicketOrderEntity::class.java) {
    fun apply(claim: RefundClaim, outcome: RefundOutcome): Boolean = jdbc.transaction {
        val operation = TicketPaymentOperations.selectAll().where {
            (TicketPaymentOperations.operationId eq claim.operationId) and
                (TicketPaymentOperations.operationKind eq "refund") and
                (TicketPaymentOperations.status eq "claimed") and
                (TicketPaymentOperations.claimToken eq claim.token) and
                (TicketPaymentOperations.claimRevision eq claim.revision)
        }.forUpdate().singleOrNull() ?: return@transaction false
        val now = clock.instant()
        if (outcome != RefundOutcome.SUCCEEDED) {
            TicketPaymentOperations.update({ TicketPaymentOperations.id eq operation[TicketPaymentOperations.id] }) {
                it[status] = if (outcome == RefundOutcome.UNKNOWN) "unknown" else "failed"
                it[nextReconcileAt] = if (outcome == RefundOutcome.UNKNOWN) now.plusSeconds(1) else null
                it[claimToken] = null
                it[claimUntil] = null
                it[revision] = revision + 1
                it[updatedAt] = now
            }
            return@transaction true
        }
        val order = TicketOrders.selectAll().where { TicketOrders.id eq claim.orderId }.forUpdate().single()
        val attemptId = order[TicketOrders.attemptId]
        val attempt = TicketPurchaseAttempts.selectAll().where { TicketPurchaseAttempts.id eq attemptId }.forUpdate().single()
        val state = PurchaseState.entries.single { it.code == attempt[TicketPurchaseAttempts.state] }
        val disposition = TicketDisposition.entries.single { it.code == order[TicketOrders.ticketDisposition] }
        val change = refundTransition(state, disposition)
        check(change.next == PurchaseState.REFUNDED) { "ticket is not safe to restock" }
        val quantity = order[TicketOrders.quantity]
        val saleId = order[TicketOrders.saleId]
        val grade = order[TicketOrders.grade]
        check(TicketInventories.update({
            (TicketInventories.saleId eq saleId) and (TicketInventories.grade eq grade)
        }) {
            it[soldQuantity] = soldQuantity + change.soldDelta * quantity
            it[revision] = revision + 1
        } == 1)
        TicketPurchaseAttempts.update({ TicketPurchaseAttempts.id eq attemptId }) {
            it[TicketPurchaseAttempts.state] = change.next.code
            it[revision] = revision + 1
            it[updatedAt] = now
        }
        TicketOrders.update({ TicketOrders.id eq claim.orderId }) {
            it[TicketOrders.state] = "refunded"
            it[revision] = revision + 1
            it[updatedAt] = now
        }
        TicketBuyerSaleStates.update({
            (TicketBuyerSaleStates.saleId eq saleId) and
                (TicketBuyerSaleStates.userSubjectId eq attempt[TicketPurchaseAttempts.userSubjectId])
        }) {
            it[purchasedQuantity] = purchasedQuantity + -quantity
            it[revision] = revision + 1
        }
        TicketActiveIdentityGuards.deleteWhere { activeAttemptId eq attemptId }
        TicketEffectReceipts.insert {
            it[consumerName] = "refund-service"
            it[operationId] = claim.operationId
            it[payloadDigest] = MessageDigest.getInstance("SHA-256")
                .digest("refund-effect-v1:${claim.operationId}".toByteArray(UTF_8))
            it[createdAt] = now
        }
        TicketPaymentOperations.update({ TicketPaymentOperations.id eq operation[TicketPaymentOperations.id] }) {
            it[status] = "succeeded"
            it[nextReconcileAt] = null
            it[claimToken] = null
            it[claimUntil] = null
            it[revision] = revision + 1
            it[updatedAt] = now
        }
        true
    }
}

/** lookup-first refund reconciliation입니다. 모든 DB work는 Exposed repository boundary 안에 머뭅니다. */
class RefundService(
    jdbc: TicketJdbcExecutor,
    private val provider: RefundProvider,
    clock: Clock = Clock.systemUTC(),
    private val claimTtl: Duration = Duration.ofSeconds(5),
    private val operations: RefundOperationRepository = RefundOperationRepository(jdbc, clock),
    private val refunds: TicketRefundRepository = TicketRefundRepository(jdbc, clock),
) {
    fun run(operationId: UUID): Boolean {
        val claim = operations.claim(operationId, claimTtl) ?: return false
        val outcome = provider.lookup(operationId) ?: provider.refund(operationId)
        return refunds.apply(claim, outcome).also { applied ->
            log.info { "refund_outcome_applied operationId=$operationId outcome=$outcome applied=$applied" }
        }
    }

    companion object : KLogging()
}
