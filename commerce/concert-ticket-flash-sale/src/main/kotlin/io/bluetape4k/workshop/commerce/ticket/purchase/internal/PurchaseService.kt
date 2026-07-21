package io.bluetape4k.workshop.commerce.ticket.purchase.internal

import io.bluetape4k.workshop.commerce.ticket.admission.api.TransactionalAdmissionCommands
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.domain.TicketDisposition
import io.bluetape4k.workshop.commerce.ticket.domain.transition
import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.InventoryRecord
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketActiveIdentityGuards
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketBuyerSaleStates
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketEffectOperations
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketHttpIdempotencies
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentitySubjects
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketInventories
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketInventoryRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketLockRank
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketOrders
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPaymentOperations
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPurchaseAttemptEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPurchaseAttempts
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketTickets
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyPaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyResult
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyTicketOutcome
import io.bluetape4k.workshop.commerce.ticket.purchase.api.AuthorizationRequested
import io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PaymentOutcomeCommands
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseCommands
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseSnapshot
import io.bluetape4k.workshop.commerce.ticket.purchase.api.StartPurchase
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePurchaseAuthority
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serial
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

fun interface PurchaseEventPublisher {
    fun publish(event: AuthorizationRequested)
}

class ActivePurchaseExists : IllegalStateException("active_purchase_exists") {
    companion object { @Serial private const val serialVersionUID: Long = 1L }
}

class InventoryUnavailable : IllegalStateException("inventory_unavailable") {
    companion object { @Serial private const val serialVersionUID: Long = 1L }
}

class PurchaseLimitExceeded : IllegalStateException("purchase_limit_exceeded") {
    companion object { @Serial private const val serialVersionUID: Long = 1L }
}

class PurchaseNotFound : IllegalStateException("purchase_not_found") {
    companion object { @Serial private const val serialVersionUID: Long = 1L }
}

class InvalidPurchaseCommand : IllegalArgumentException("invalid_purchase_command") {
    companion object { @Serial private const val serialVersionUID: Long = 1L }
}

class IdempotencyOwnershipLost : IllegalStateException("idempotency_ownership_lost") {
    companion object { @Serial private const val serialVersionUID: Long = 1L }
}

/** Purchase aggregate persistence implemented on Bluetape4k [TicketExposedJdbcRepository]. */
class TicketPurchaseRepository(
    private val jdbc: TicketJdbcExecutor,
    private val sale: SalePurchaseAuthority,
    private val admission: TransactionalAdmissionCommands,
    private val clock: Clock,
) : TicketExposedJdbcRepository<TicketPurchaseAttemptEntity, UUID>(TicketPurchaseAttemptEntity::class.java) {
    private val inventories = TicketInventoryRepository(jdbc)

    fun start(command: StartPurchase): PurchaseCommit = jdbc.transaction {
        val now = clock.instant()
        val policy = sale.requireOpen(this, command.policy, now)
        if (command.quantity !in 1..policy.maxQuantity) throw PurchaseLimitExceeded()

        lockIdempotency(command)?.let { return@transaction PurchaseCommit(loadSnapshot(it), null) }
        lockGuard(command.policy.saleId, IdentityKind.USER, command.buyerSubjectId, null)
        lockGuard(command.policy.saleId, IdentityKind.IP, command.ipSubjectId, null)
        lockBuyer(command.policy.saleId, command.buyerSubjectId, policy.policyVersion, command.quantity, policy.perUserLimit)
        val inventory = inventories.lock(this, command.policy.saleId, command.grade)
        if (inventory.available < command.quantity) throw InventoryUnavailable()

        insertAttempt(command, policy.policyVersion, now.plusSeconds(policy.holdSeconds), now)
        admission.consume(this, command.grant)
        applyInventoryDelta(inventory, heldDelta = command.quantity)
        insertGuard(command.policy.saleId, IdentityKind.USER, command.buyerSubjectId, command.attemptId, now)
        insertGuard(command.policy.saleId, IdentityKind.IP, command.ipSubjectId, command.attemptId, now)
        bindIdempotency(command.idempotencyOwnerId, command.attemptId, now)
        insertPaymentOperation(command, now)

        PurchaseCommit(
            PurchaseSnapshot(command.attemptId, PurchaseState.INVENTORY_HELD, 0, now),
            AuthorizationRequested(UUID.randomUUID(), command.attemptId, command.authorizationOperationId),
        )
    }

    fun cancel(command: CancelPurchase): PurchaseSnapshot = jdbc.transaction {
        val candidate = findAttempt(command.attemptId) ?: throw PurchaseNotFound()
        if (candidate.buyerSubjectId != command.buyerSubjectId) throw PurchaseNotFound()
        lockGuard(candidate.saleId, IdentityKind.USER, candidate.buyerSubjectId, candidate.attemptId)
        lockGuard(candidate.saleId, IdentityKind.IP, candidate.ipSubjectId, candidate.attemptId)
        lockExistingBuyer(candidate.saleId, candidate.buyerSubjectId)
        val inventory = inventories.lock(this, candidate.saleId, candidate.grade)
        acquire(TicketLockRank.ATTEMPT_ORDER)
        val attempt = lockAttempt(command.attemptId, command.buyerSubjectId)
        when (attempt.state) {
            PurchaseState.INVENTORY_HELD -> {
                applyInventoryDelta(inventory, heldDelta = -attempt.quantity)
                updateAttemptState(attempt.attemptId, PurchaseState.CANCELLED)
                releaseGuards(attempt.attemptId)
                attempt.snapshot(PurchaseState.CANCELLED, clock.instant())
            }
            PurchaseState.PAYMENT_AUTHORIZING, PurchaseState.RECONCILIATION_REQUIRED -> {
                updateAttemptState(attempt.attemptId, PurchaseState.CANCELLATION_REQUESTED)
                attempt.snapshot(PurchaseState.CANCELLATION_REQUESTED, clock.instant())
            }
            else -> attempt.snapshot(attempt.state, attempt.updatedAt)
        }
    }

    fun applyPaymentOutcome(command: ApplyPaymentOutcome): ApplyResult = jdbc.transaction {
        val candidate = findAttempt(command.attemptId) ?: return@transaction ApplyResult.STALE
        lockGuard(candidate.saleId, IdentityKind.USER, candidate.buyerSubjectId, candidate.attemptId)
        lockGuard(candidate.saleId, IdentityKind.IP, candidate.ipSubjectId, candidate.attemptId)
        lockExistingBuyer(candidate.saleId, candidate.buyerSubjectId)
        val inventory = inventories.lock(this, candidate.saleId, candidate.grade)
        acquire(TicketLockRank.ATTEMPT_ORDER)
        val attempt = lockAttempt(candidate.attemptId, candidate.buyerSubjectId)
        acquire(TicketLockRank.EFFECT)
        if (!ownsPaymentClaim(command)) return@transaction ApplyResult.STALE

        val change = transition(attempt.state, command.outcome)
        applyInventoryDelta(inventory, change.heldDelta * attempt.quantity, change.soldDelta * attempt.quantity)
        updateAttemptState(attempt.attemptId, change.next)
        if (change.releaseGuards) releaseGuards(attempt.attemptId)
        if (change.soldDelta > 0) incrementPurchased(attempt.saleId, attempt.buyerSubjectId, attempt.quantity)
        if (change.next == PurchaseState.APPROVED || change.next == PurchaseState.REFUND_PENDING) {
            insertOrder(attempt, command.operationId, change.ticketDisposition ?: TicketDisposition.PENDING)
        }
        completePaymentOperation(command, change.next)
        ApplyResult.APPLIED
    }

    private fun TicketJdbcTransaction.lockIdempotency(command: StartPurchase): UUID? {
        acquire(TicketLockRank.IDEMPOTENCY)
        val row = TicketHttpIdempotencies.selectAll()
            .where {
                (TicketHttpIdempotencies.id eq command.idempotencyOwnerId) and
                    (TicketHttpIdempotencies.principalSubjectId eq command.buyerSubjectId)
            }
            .forUpdate()
            .singleOrNull()
            ?: throw IdempotencyOwnershipLost()
        if (row[TicketHttpIdempotencies.status] != "in_progress") throw IdempotencyOwnershipLost()
        return row[TicketHttpIdempotencies.attemptId]?.also {
            if (it != command.attemptId) throw IdempotencyOwnershipLost()
        }
    }

    private fun TicketJdbcTransaction.lockGuard(
        saleId: UUID,
        kind: IdentityKind,
        subjectId: UUID,
        expectedAttempt: UUID?,
    ) {
        acquire(if (kind == IdentityKind.USER) TicketLockRank.USER_GUARD else TicketLockRank.IP_GUARD)
        exposed.exec("SELECT pg_advisory_xact_lock(${guardAdvisoryKey(saleId, kind, subjectId)})") {
            check(it.next())
        }
        val subjectKind = TicketIdentitySubjects.selectAll()
            .where { TicketIdentitySubjects.id eq subjectId }
            .singleOrNull()
            ?.get(TicketIdentitySubjects.identityKind)
        if (subjectKind != kind.name) throw InvalidPurchaseCommand()
        val activeAttempt = TicketActiveIdentityGuards.selectAll()
            .where {
                (TicketActiveIdentityGuards.saleId eq saleId) and
                    (TicketActiveIdentityGuards.identityKind eq kind.name) and
                    (TicketActiveIdentityGuards.identitySubjectId eq subjectId)
            }
            .singleOrNull()
            ?.get(TicketActiveIdentityGuards.activeAttemptId)
        if (activeAttempt != null && activeAttempt != expectedAttempt) throw ActivePurchaseExists()
    }

    private fun TicketJdbcTransaction.lockBuyer(
        saleId: UUID,
        buyerId: UUID,
        policyVersion: Long,
        quantity: Int,
        perUserLimit: Int,
    ) {
        acquire(TicketLockRank.BUYER)
        if (TicketBuyerSaleStates.selectAll().where {
                (TicketBuyerSaleStates.saleId eq saleId) and (TicketBuyerSaleStates.userSubjectId eq buyerId)
            }.empty()
        ) {
            TicketBuyerSaleStates.insert {
                it[TicketBuyerSaleStates.saleId] = saleId
                it[userSubjectId] = buyerId
                it[TicketBuyerSaleStates.policyVersion] = policyVersion
                it[purchasedQuantity] = 0
                it[revision] = 0
            }
        }
        val purchased = TicketBuyerSaleStates.selectAll()
            .where { (TicketBuyerSaleStates.saleId eq saleId) and (TicketBuyerSaleStates.userSubjectId eq buyerId) }
            .forUpdate()
            .single()[TicketBuyerSaleStates.purchasedQuantity]
        if (purchased + quantity > perUserLimit) throw PurchaseLimitExceeded()
    }

    private fun TicketJdbcTransaction.lockExistingBuyer(saleId: UUID, buyerId: UUID) {
        acquire(TicketLockRank.BUYER)
        check(
            TicketBuyerSaleStates.selectAll()
                .where { (TicketBuyerSaleStates.saleId eq saleId) and (TicketBuyerSaleStates.userSubjectId eq buyerId) }
                .forUpdate()
                .singleOrNull() != null,
        ) { "buyer state not found" }
    }

    private fun TicketJdbcTransaction.insertAttempt(
        command: StartPurchase,
        policyVersion: Long,
        holdDeadline: Instant,
        now: Instant,
    ) {
        acquire(TicketLockRank.ATTEMPT_ORDER)
        save(TicketPurchaseAttemptEntity.new(command.attemptId) {
            saleId = command.policy.saleId
            userSubjectId = command.buyerSubjectId
            ipSubjectId = command.ipSubjectId
            grade = command.grade
            quantity = command.quantity
            this.policyVersion = policyVersion
            state = PurchaseState.INVENTORY_HELD.code
            this.holdDeadline = holdDeadline
            authorizationOperationId = command.authorizationOperationId
            revision = 0
            createdAt = now
            updatedAt = now
        })
    }

    private fun TicketJdbcTransaction.applyInventoryDelta(
        inventory: InventoryRecord,
        heldDelta: Int,
        soldDelta: Int = 0,
    ) {
        check(TicketInventories.update({
            (TicketInventories.saleId eq inventory.saleId) and
                (TicketInventories.grade eq inventory.grade) and
                (TicketInventories.revision eq inventory.revision)
        }) {
            it[heldQuantity] = heldQuantity + heldDelta
            it[soldQuantity] = soldQuantity + soldDelta
            it[revision] = revision + 1
        } == 1) { "inventory revision changed while locked" }
    }

    private fun TicketJdbcTransaction.insertPaymentOperation(command: StartPurchase, now: Instant) {
        acquire(TicketLockRank.EFFECT)
        TicketPaymentOperations.insert {
            it[provider] = "fake"
            it[operationId] = command.authorizationOperationId
            it[attemptId] = command.attemptId
            it[operationKind] = "authorize"
            it[status] = "pending"
            it[nextReconcileAt] = now
            it[claimRevision] = 0
            it[revision] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    private fun ownsPaymentClaim(command: ApplyPaymentOutcome): Boolean =
        TicketPaymentOperations.selectAll().where {
            (TicketPaymentOperations.operationId eq command.operationId) and
                (TicketPaymentOperations.attemptId eq command.attemptId) and
                (TicketPaymentOperations.status eq "claimed") and
                (TicketPaymentOperations.claimToken eq command.claimToken) and
                (TicketPaymentOperations.claimRevision eq command.claimRevision)
        }.forUpdate().singleOrNull() != null

    private fun completePaymentOperation(command: ApplyPaymentOutcome, nextState: PurchaseState) {
        val status = when (command.outcome) {
            PaymentOutcome.UNKNOWN -> "unknown"
            PaymentOutcome.APPROVED -> "approved"
            PaymentOutcome.DECLINED -> "declined"
            else -> error("unsupported provider outcome")
        }
        val now = clock.instant()
        check(TicketPaymentOperations.update({
            (TicketPaymentOperations.operationId eq command.operationId) and
                (TicketPaymentOperations.claimToken eq command.claimToken) and
                (TicketPaymentOperations.claimRevision eq command.claimRevision)
        }) {
            it[TicketPaymentOperations.status] = status
            it[nextReconcileAt] = if (status == "unknown") now.plusSeconds(1) else null
            it[claimToken] = null
            it[claimUntil] = null
            it[revision] = revision + 1
            it[updatedAt] = now
        } == 1) { "payment claim changed while locked: $nextState" }
    }

    private fun incrementPurchased(saleId: UUID, buyerId: UUID, quantity: Int) {
        check(TicketBuyerSaleStates.update({
            (TicketBuyerSaleStates.saleId eq saleId) and (TicketBuyerSaleStates.userSubjectId eq buyerId)
        }) {
            it[purchasedQuantity] = purchasedQuantity + quantity
            it[revision] = revision + 1
        } == 1)
    }

    private fun insertOrder(attempt: AttemptRecord, authorizationOperationId: UUID, disposition: TicketDisposition) {
        val now = clock.instant()
        val orderId = UUID.randomUUID()
        val refundOperationId = if (disposition == TicketDisposition.NEVER_ISSUED) stableOperationId("refund", orderId) else null
        TicketOrders.insert {
            it[id] = orderId
            it[TicketOrders.attemptId] = attempt.attemptId
            it[saleId] = attempt.saleId
            it[grade] = attempt.grade
            it[quantity] = attempt.quantity
            it[state] = if (refundOperationId != null) "refund_pending" else "paid"
            it[ticketDisposition] = disposition.code
            it[TicketOrders.authorizationOperationId] = authorizationOperationId
            it[TicketOrders.refundOperationId] = refundOperationId
            it[revision] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }
        if (refundOperationId != null) {
            TicketPaymentOperations.insert {
                it[provider] = "fake"
                it[operationId] = refundOperationId
                it[attemptId] = attempt.attemptId
                it[TicketPaymentOperations.orderId] = orderId
                it[operationKind] = "refund"
                it[status] = "pending"
                it[nextReconcileAt] = now
                it[claimRevision] = 0
                it[revision] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
        } else {
            TicketTickets.insert {
                it[TicketTickets.orderId] = orderId
                it[state] = "issue_pending"
                it[revision] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
            TicketEffectOperations.insert {
                it[effectKind] = "issue"
                it[operationId] = stableOperationId("issue", orderId)
                it[TicketEffectOperations.orderId] = orderId
                it[status] = "pending"
                it[claimRevision] = 0
                it[revision] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    private fun insertGuard(saleId: UUID, kind: IdentityKind, subjectId: UUID, attemptId: UUID, now: Instant) {
        TicketActiveIdentityGuards.insert {
            it[TicketActiveIdentityGuards.saleId] = saleId
            it[identityKind] = kind.name
            it[identitySubjectId] = subjectId
            it[activeAttemptId] = attemptId
            it[createdAt] = now
        }
    }

    private fun bindIdempotency(ownerId: Long, attemptId: UUID, now: Instant) {
        if (TicketHttpIdempotencies.update({
                (TicketHttpIdempotencies.id eq ownerId) and
                    (TicketHttpIdempotencies.status eq "in_progress") and
                    TicketHttpIdempotencies.attemptId.isNull()
            }) {
                it[TicketHttpIdempotencies.attemptId] = attemptId
                it[updatedAt] = now
            } != 1
        ) throw IdempotencyOwnershipLost()
    }

    private fun findAttempt(attemptId: UUID): AttemptRecord? =
        findById(attemptId).orElse(null)?.toRecord()

    private fun lockAttempt(attemptId: UUID, buyerId: UUID): AttemptRecord =
        TicketPurchaseAttempts.selectAll()
            .where { (TicketPurchaseAttempts.id eq attemptId) and (TicketPurchaseAttempts.userSubjectId eq buyerId) }
            .forUpdate()
            .singleOrNull()
            ?.let { row ->
                findById(row[TicketPurchaseAttempts.id].value).orElse(null)?.toRecord()
            }
            ?: throw PurchaseNotFound()

    private fun loadSnapshot(attemptId: UUID): PurchaseSnapshot =
        findAttempt(attemptId)?.snapshot() ?: throw IdempotencyOwnershipLost()

    private fun updateAttemptState(attemptId: UUID, state: PurchaseState) {
        check(TicketPurchaseAttempts.update({ TicketPurchaseAttempts.id eq attemptId }) {
            it[TicketPurchaseAttempts.state] = state.code
            it[revision] = revision + 1
            it[updatedAt] = clock.instant()
        } == 1)
    }

    private fun releaseGuards(attemptId: UUID) {
        TicketActiveIdentityGuards.deleteWhere { activeAttemptId eq attemptId }
    }

    private fun TicketPurchaseAttemptEntity.toRecord() = AttemptRecord(
        attemptId = id.value,
        saleId = saleId,
        buyerSubjectId = userSubjectId,
        ipSubjectId = ipSubjectId,
        grade = grade,
        quantity = quantity,
        state = PurchaseState.entries.single { it.code == state },
        revision = revision,
        updatedAt = updatedAt,
    )

    private fun stableOperationId(kind: String, orderId: UUID): UUID =
        UUID.nameUUIDFromBytes("ticket-$kind-v1:$orderId".toByteArray(UTF_8))

    private fun guardAdvisoryKey(saleId: UUID, kind: IdentityKind, subjectId: UUID): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "ticket-active-guard-v1\u0000$saleId\u0000${kind.name}\u0000$subjectId".toByteArray(UTF_8),
        )
        return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    }

    data class PurchaseCommit(val snapshot: PurchaseSnapshot, val event: AuthorizationRequested?)

    private data class AttemptRecord(
        val attemptId: UUID,
        val saleId: UUID,
        val buyerSubjectId: UUID,
        val ipSubjectId: UUID,
        val grade: String,
        val quantity: Int,
        val state: PurchaseState,
        val revision: Long,
        val updatedAt: Instant,
    ) {
        fun snapshot(nextState: PurchaseState = state, at: Instant = updatedAt) = PurchaseSnapshot(
            attemptId = attemptId,
            state = nextState,
            revision = if (nextState == state) revision else revision + 1,
            updatedAt = at,
        )
    }

    private val InventoryRecord.available: Int get() = total - held - sold
}

/** Application service; all database work is delegated to [TicketPurchaseRepository]. */
class PurchaseService(
    jdbc: TicketJdbcExecutor,
    sale: SalePurchaseAuthority,
    admission: TransactionalAdmissionCommands,
    clock: Clock,
    private val events: PurchaseEventPublisher,
    private val purchases: TicketPurchaseRepository = TicketPurchaseRepository(jdbc, sale, admission, clock),
) : PurchaseCommands, PaymentOutcomeCommands {
    override fun start(command: StartPurchase): PurchaseSnapshot {
        validate(command)
        val committed = purchases.start(command)
        committed.event?.let(events::publish)
        return committed.snapshot
    }

    override fun cancel(command: CancelPurchase): PurchaseSnapshot = purchases.cancel(command)

    override fun applyPaymentOutcome(command: ApplyPaymentOutcome): ApplyResult = purchases.applyPaymentOutcome(command)

    override fun applyTicketOutcome(command: ApplyTicketOutcome): PurchaseSnapshot =
        throw UnsupportedOperationException("implemented by the ticket effect task")

    private fun validate(command: StartPurchase) {
        if (command.idempotencyOwnerId <= 0 || command.grade.isBlank() || command.quantity <= 0 ||
            command.grant.saleId != command.policy.saleId ||
            command.grant.buyerSubjectId != command.buyerSubjectId ||
            command.grant.policyVersion != command.policy.policyVersion ||
            command.grant.attemptId != command.attemptId
        ) throw InvalidPurchaseCommand()
    }
}
