package io.bluetape4k.workshop.commerce.ticket.purchase.internal

import io.bluetape4k.workshop.commerce.ticket.admission.api.TransactionalAdmissionCommands
import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.InventoryRecord
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketInventoryRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketLockRank
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyPaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyTicketOutcome
import io.bluetape4k.workshop.commerce.ticket.purchase.api.AuthorizationRequested
import io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseCommands
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseSnapshot
import io.bluetape4k.workshop.commerce.ticket.purchase.api.StartPurchase
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePurchaseAuthority
import java.io.Serial
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

fun interface PurchaseEventPublisher {
    fun publish(event: AuthorizationRequested)
}

class ActivePurchaseExists : IllegalStateException("active_purchase_exists") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

class InventoryUnavailable : IllegalStateException("inventory_unavailable") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

class PurchaseLimitExceeded : IllegalStateException("purchase_limit_exceeded") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

class PurchaseNotFound : IllegalStateException("purchase_not_found") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

class InvalidPurchaseCommand : IllegalArgumentException("invalid_purchase_command") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

class IdempotencyOwnershipLost : IllegalStateException("idempotency_ownership_lost") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Serializes a purchase hold at PostgreSQL's USER/IP, buyer, and inventory authorities. */
class PurchaseService(
    private val jdbc: TicketJdbcExecutor,
    private val sale: SalePurchaseAuthority,
    private val admission: TransactionalAdmissionCommands,
    private val clock: Clock,
    private val events: PurchaseEventPublisher,
) : PurchaseCommands {
    private val inventories = TicketInventoryRepository(jdbc)

    override fun start(command: StartPurchase): PurchaseSnapshot {
        validate(command)
        val committed =
            jdbc.transaction {
                val now = clock.instant()
                val policy = sale.requireOpen(this, command.policy, now)
                if (command.quantity !in 1..policy.maxQuantity) throw PurchaseLimitExceeded()

                val replayAttempt = lockIdempotency(command)
                if (replayAttempt != null) {
                    return@transaction PurchaseCommit(loadSnapshot(replayAttempt), null)
                }
                lockGuard(command.policy.saleId, IdentityKind.USER, command.buyerSubjectId, expectedAttempt = null)
                lockGuard(command.policy.saleId, IdentityKind.IP, command.ipSubjectId, expectedAttempt = null)
                lockBuyer(command.policy.saleId, command.buyerSubjectId, policy.policyVersion, command.quantity, policy.perUserLimit)
                val inventory = inventories.lock(this, command.policy.saleId, command.grade)
                if (inventory.available < command.quantity) throw InventoryUnavailable()

                insertAttempt(command, policy.policyVersion, now.plusSeconds(policy.holdSeconds))
                admission.consume(this, command.grant)
                applyInventoryDelta(inventory, heldDelta = command.quantity)
                insertGuard(command.policy.saleId, IdentityKind.USER, command.buyerSubjectId, command.attemptId)
                insertGuard(command.policy.saleId, IdentityKind.IP, command.ipSubjectId, command.attemptId)
                bindIdempotency(command.idempotencyOwnerId, command.attemptId)

                val snapshot = PurchaseSnapshot(command.attemptId, PurchaseState.INVENTORY_HELD, 0, now)
                PurchaseCommit(
                    snapshot,
                    AuthorizationRequested(UUID.randomUUID(), command.attemptId, command.authorizationOperationId),
                )
            }
        committed.event?.let(events::publish)
        return committed.snapshot
    }

    override fun cancel(command: CancelPurchase): PurchaseSnapshot =
        jdbc.transaction {
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
                PurchaseState.PAYMENT_AUTHORIZING,
                PurchaseState.RECONCILIATION_REQUIRED,
                -> {
                    updateAttemptState(attempt.attemptId, PurchaseState.CANCELLATION_REQUESTED)
                    attempt.snapshot(PurchaseState.CANCELLATION_REQUESTED, clock.instant())
                }
                else -> attempt.snapshot(attempt.state, attempt.updatedAt)
            }
        }

    override fun applyPaymentOutcome(command: ApplyPaymentOutcome): PurchaseSnapshot =
        throw UnsupportedOperationException("implemented by the payment fencing task")

    override fun applyTicketOutcome(command: ApplyTicketOutcome): PurchaseSnapshot =
        throw UnsupportedOperationException("implemented by the ticket effect task")

    private fun validate(command: StartPurchase) {
        if (command.idempotencyOwnerId <= 0 || command.grade.isBlank() || command.quantity <= 0 ||
            command.grant.saleId != command.policy.saleId ||
            command.grant.buyerSubjectId != command.buyerSubjectId ||
            command.grant.policyVersion != command.policy.policyVersion ||
            command.grant.attemptId != command.attemptId
        ) {
            throw InvalidPurchaseCommand()
        }
    }

    private fun TicketJdbcTransaction.lockIdempotency(command: StartPurchase): UUID? {
        acquire(TicketLockRank.IDEMPOTENCY)
        connection.prepareStatement(
            """
            SELECT status, attempt_id FROM ticket_http_idempotency
            WHERE id = ? AND principal_subject_id = ? FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, command.idempotencyOwnerId)
            statement.setObject(2, command.buyerSubjectId)
            statement.executeQuery().use { result ->
                if (!result.next() || result.getString("status") != "in_progress") throw IdempotencyOwnershipLost()
                val attemptId = result.getObject("attempt_id", UUID::class.java)
                if (attemptId != null && attemptId != command.attemptId) throw IdempotencyOwnershipLost()
                return attemptId
            }
        }
    }

    private fun TicketJdbcTransaction.lockGuard(
        saleId: UUID,
        kind: IdentityKind,
        subjectId: UUID,
        expectedAttempt: UUID?,
    ) {
        acquire(if (kind == IdentityKind.USER) TicketLockRank.USER_GUARD else TicketLockRank.IP_GUARD)
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, guardAdvisoryKey(saleId, kind, subjectId))
            statement.executeQuery().use { result -> check(result.next()) }
        }
        connection.prepareStatement(
            """
            SELECT s.identity_kind, g.active_attempt_id
            FROM ticket_identity_subjects s
            LEFT JOIN ticket_active_identity_guards g
              ON g.sale_id = ? AND g.identity_kind = ? AND g.identity_subject_id = s.subject_id
            WHERE s.subject_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, saleId)
            statement.setString(2, kind.name)
            statement.setObject(3, subjectId)
            statement.executeQuery().use { result ->
                if (!result.next() || result.getString("identity_kind") != kind.name) throw InvalidPurchaseCommand()
                val activeAttempt = result.getObject("active_attempt_id", UUID::class.java)
                if (activeAttempt != null && activeAttempt != expectedAttempt) throw ActivePurchaseExists()
            }
        }
    }

    private fun TicketJdbcTransaction.lockBuyer(
        saleId: UUID,
        buyerId: UUID,
        policyVersion: Long,
        quantity: Int,
        perUserLimit: Int,
    ) {
        acquire(TicketLockRank.BUYER)
        connection.prepareStatement(
            """
            INSERT INTO ticket_buyer_sale_states(sale_id, user_subject_id, policy_version)
            VALUES (?, ?, ?) ON CONFLICT (sale_id, user_subject_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, saleId)
            statement.setObject(2, buyerId)
            statement.setLong(3, policyVersion)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "SELECT purchased_quantity FROM ticket_buyer_sale_states WHERE sale_id = ? AND user_subject_id = ? FOR UPDATE",
        ).use { statement ->
            statement.setObject(1, saleId)
            statement.setObject(2, buyerId)
            statement.executeQuery().use { result ->
                check(result.next())
                if (result.getInt(1) + quantity > perUserLimit) throw PurchaseLimitExceeded()
            }
        }
    }

    private fun TicketJdbcTransaction.lockExistingBuyer(
        saleId: UUID,
        buyerId: UUID,
    ) {
        acquire(TicketLockRank.BUYER)
        connection.prepareStatement(
            "SELECT 1 FROM ticket_buyer_sale_states WHERE sale_id = ? AND user_subject_id = ? FOR UPDATE",
        ).use { statement ->
            statement.setObject(1, saleId)
            statement.setObject(2, buyerId)
            statement.executeQuery().use { result -> check(result.next()) { "buyer state not found" } }
        }
    }

    private fun TicketJdbcTransaction.insertAttempt(
        command: StartPurchase,
        policyVersion: Long,
        holdDeadline: Instant,
    ) {
        acquire(TicketLockRank.ATTEMPT_ORDER)
        connection.prepareStatement(
            """
            INSERT INTO ticket_purchase_attempts(
                attempt_id, sale_id, user_subject_id, ip_subject_id, grade, quantity, policy_version,
                state, hold_deadline, authorization_operation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'inventory_held', ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, command.attemptId)
            statement.setObject(2, command.policy.saleId)
            statement.setObject(3, command.buyerSubjectId)
            statement.setObject(4, command.ipSubjectId)
            statement.setString(5, command.grade)
            statement.setInt(6, command.quantity)
            statement.setLong(7, policyVersion)
            statement.setObject(8, holdDeadline.atOffset(ZoneOffset.UTC))
            statement.setObject(9, command.authorizationOperationId)
            statement.executeUpdate()
        }
    }

    private fun TicketJdbcTransaction.applyInventoryDelta(
        inventory: InventoryRecord,
        heldDelta: Int,
    ) {
        connection.prepareStatement(
            """
            UPDATE ticket_inventory
            SET held_quantity = held_quantity + ?, revision = revision + 1
            WHERE sale_id = ? AND grade = ? AND revision = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, heldDelta)
            statement.setObject(2, inventory.saleId)
            statement.setString(3, inventory.grade)
            statement.setLong(4, inventory.revision)
            check(statement.executeUpdate() == 1) { "inventory revision changed while locked" }
        }
    }

    private fun TicketJdbcTransaction.insertGuard(
        saleId: UUID,
        kind: IdentityKind,
        subjectId: UUID,
        attemptId: UUID,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO ticket_active_identity_guards(
                sale_id, identity_kind, identity_subject_id, active_attempt_id
            ) VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, saleId)
            statement.setString(2, kind.name)
            statement.setObject(3, subjectId)
            statement.setObject(4, attemptId)
            statement.executeUpdate()
        }
    }

    private fun TicketJdbcTransaction.bindIdempotency(
        ownerId: Long,
        attemptId: UUID,
    ) {
        connection.prepareStatement(
            """
            UPDATE ticket_http_idempotency SET attempt_id = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'in_progress' AND attempt_id IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, attemptId)
            statement.setLong(2, ownerId)
            if (statement.executeUpdate() != 1) throw IdempotencyOwnershipLost()
        }
    }

    private fun TicketJdbcTransaction.findAttempt(attemptId: UUID): AttemptRecord? =
        connection.prepareStatement(
            """
            SELECT attempt_id, sale_id, user_subject_id, ip_subject_id, grade, quantity, state, revision, updated_at
            FROM ticket_purchase_attempts WHERE attempt_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, attemptId)
            statement.executeQuery().use { result -> if (result.next()) result.toAttempt() else null }
        }

    private fun TicketJdbcTransaction.lockAttempt(
        attemptId: UUID,
        buyerId: UUID,
    ): AttemptRecord =
        connection.prepareStatement(
            """
            SELECT attempt_id, sale_id, user_subject_id, ip_subject_id, grade, quantity, state, revision, updated_at
            FROM ticket_purchase_attempts WHERE attempt_id = ? AND user_subject_id = ? FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, attemptId)
            statement.setObject(2, buyerId)
            statement.executeQuery().use { result -> if (result.next()) result.toAttempt() else throw PurchaseNotFound() }
        }

    private fun TicketJdbcTransaction.loadSnapshot(attemptId: UUID): PurchaseSnapshot =
        findAttempt(attemptId)?.snapshot() ?: throw IdempotencyOwnershipLost()

    private fun TicketJdbcTransaction.updateAttemptState(
        attemptId: UUID,
        state: PurchaseState,
    ) {
        connection.prepareStatement(
            """
            UPDATE ticket_purchase_attempts
            SET state = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE attempt_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, state.code)
            statement.setObject(2, attemptId)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun TicketJdbcTransaction.releaseGuards(attemptId: UUID) {
        connection.prepareStatement("DELETE FROM ticket_active_identity_guards WHERE active_attempt_id = ?").use { statement ->
            statement.setObject(1, attemptId)
            statement.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.toAttempt(): AttemptRecord =
        AttemptRecord(
            attemptId = getObject("attempt_id", UUID::class.java),
            saleId = getObject("sale_id", UUID::class.java),
            buyerSubjectId = getObject("user_subject_id", UUID::class.java),
            ipSubjectId = getObject("ip_subject_id", UUID::class.java),
            grade = getString("grade"),
            quantity = getInt("quantity"),
            state = PurchaseState.entries.single { it.code == getString("state") },
            revision = getLong("revision"),
            updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        )

    private fun guardAdvisoryKey(
        saleId: UUID,
        kind: IdentityKind,
        subjectId: UUID,
    ): Long {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(
                "ticket-active-guard-v1\u0000$saleId\u0000${kind.name}\u0000$subjectId".toByteArray(UTF_8),
            )
        return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    }

    private data class PurchaseCommit(
        val snapshot: PurchaseSnapshot,
        val event: AuthorizationRequested?,
    )

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
        fun snapshot(
            nextState: PurchaseState = state,
            at: Instant = updatedAt,
        ): PurchaseSnapshot =
            PurchaseSnapshot(
                attemptId = attemptId,
                state = nextState,
                revision = if (nextState == state) revision else revision + 1,
                updatedAt = at,
            )
    }

    private val InventoryRecord.available: Int get() = total - held - sold
}
