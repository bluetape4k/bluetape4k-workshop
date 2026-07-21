package io.bluetape4k.workshop.commerce.ticket.persistence

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import io.bluetape4k.idgenerators.uuid.Uuid
import java.util.UUID

/** PostgreSQL inventory authority backed by Bluetape4k [TicketExposedJdbcRepository]. */
class TicketInventoryRepository(
    private val jdbc: TicketJdbcExecutor,
) : TicketExposedJdbcRepository<TicketInventoryEntity, Long>(TicketInventoryEntity::class.java) {

    fun get(saleId: UUID, grade: String): InventoryRecord =
        jdbc.transaction {
            findAll { (TicketInventories.saleId eq saleId) and (TicketInventories.grade eq grade) }
                .singleOrNull()
                ?.toRecord()
                ?: error("inventory not found")
        }

    fun forceQuantities(saleId: UUID, grade: String, held: Int, sold: Int) {
        jdbc.transaction {
            acquire(TicketLockRank.INVENTORY)
            check(
                TicketInventories.update({
                    (TicketInventories.saleId eq saleId) and (TicketInventories.grade eq grade)
                }) {
                    it[heldQuantity] = held
                    it[soldQuantity] = sold
                    it[revision] = revision + 1
                } == 1,
            ) { "inventory not found" }
        }
    }

    fun lock(transaction: TicketJdbcTransaction, saleId: UUID, grade: String): InventoryRecord =
        with(transaction) {
            acquire(TicketLockRank.INVENTORY)
            TicketInventories.selectAll()
                .where { (TicketInventories.saleId eq saleId) and (TicketInventories.grade eq grade) }
                .forUpdate()
                .singleOrNull()
                ?.let {
                    InventoryRecord(
                        saleId = it[TicketInventories.saleId],
                        grade = it[TicketInventories.grade],
                        total = it[TicketInventories.totalQuantity],
                        held = it[TicketInventories.heldQuantity],
                        sold = it[TicketInventories.soldQuantity],
                        revision = it[TicketInventories.revision],
                    )
                }
                ?: error("inventory not found")
        }
}

/** Durable USER/IP guard authority backed by Bluetape4k [TicketExposedJdbcRepository]. */
class TicketIdentityGuardRepository(
    private val jdbc: TicketJdbcExecutor,
) : TicketExposedJdbcRepository<TicketActiveIdentityGuardEntity, Long>(
    TicketActiveIdentityGuardEntity::class.java,
) {
    fun insert(saleId: UUID, kind: IdentityKind, subjectId: UUID, attemptId: UUID) {
        jdbc.transaction {
            acquire(if (kind == IdentityKind.USER) TicketLockRank.USER_GUARD else TicketLockRank.IP_GUARD)
            TicketActiveIdentityGuards.insert {
                it[TicketActiveIdentityGuards.saleId] = saleId
                it[identityKind] = kind.name
                it[identitySubjectId] = subjectId
                it[activeAttemptId] = attemptId
            }
        }
    }
}

/** Durable FIFO queue backed by Bluetape4k [TicketExposedJdbcRepository]. */
class TicketWaitingRoomRepository(
    private val jdbc: TicketJdbcExecutor,
) : TicketExposedJdbcRepository<TicketWaitingRoomEntryEntity, Long>(
    TicketWaitingRoomEntryEntity::class.java,
) {
    fun join(saleId: UUID, userSubjectId: UUID, sequence: Long): UUID {
        val entryId = Uuid.V7.nextId()
        val now = Instant.now()
        jdbc.transaction {
            TicketWaitingRoomEntries.insert {
                it[TicketWaitingRoomEntries.entryId] = entryId
                it[TicketWaitingRoomEntries.saleId] = saleId
                it[TicketWaitingRoomEntries.userSubjectId] = userSubjectId
                it[state] = "waiting"
                it[TicketWaitingRoomEntries.sequence] = sequence
                it[revision] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return entryId
    }

    fun claimBatch(saleId: UUID, limit: Int): List<WaitingEntryRecord> =
        jdbc.transaction {
            TicketWaitingRoomEntries.selectAll()
                .where {
                    (TicketWaitingRoomEntries.saleId eq saleId) and
                        (TicketWaitingRoomEntries.state eq "waiting")
                }
                .orderBy(TicketWaitingRoomEntries.sequence to SortOrder.ASC, TicketWaitingRoomEntries.id to SortOrder.ASC)
                .limit(limit)
                .forUpdate(
                    ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED),
                )
                .map {
                    WaitingEntryRecord(
                        id = it[TicketWaitingRoomEntries.id].value,
                        entryId = it[TicketWaitingRoomEntries.entryId],
                        saleId = it[TicketWaitingRoomEntries.saleId],
                        userSubjectId = it[TicketWaitingRoomEntries.userSubjectId],
                        sequence = it[TicketWaitingRoomEntries.sequence],
                    )
                }
        }

    /** PostgreSQL-specific EXPLAIN is intentionally isolated from normal CRUD. */
    fun explainClaim(saleId: UUID): List<String> =
        jdbc.transaction {
            exposed.exec("SET LOCAL enable_seqscan = off")
            exposed.exec(
                """
                EXPLAIN SELECT id FROM ticket_waiting_room_entries
                WHERE sale_id = '$saleId'::uuid AND state = 'waiting'
                ORDER BY sequence, id LIMIT 50 FOR UPDATE SKIP LOCKED
                """.trimIndent(),
                explicitStatementType = StatementType.SELECT,
            ) { result -> buildList { while (result.next()) add(result.getString(1)) } } ?: emptyList()
        }
}

/** Payment-operation ledger backed by Bluetape4k [TicketExposedJdbcRepository]. */
class TicketPaymentOperationRepository(
    private val jdbc: TicketJdbcExecutor,
) : TicketExposedJdbcRepository<TicketPaymentOperationEntity, Long>(
    TicketPaymentOperationEntity::class.java,
) {
    fun insertAuthorization(provider: String, operationId: UUID, attemptId: UUID) {
        val authorizationProvider = provider
        val authorizationOperationId = operationId
        val purchaseAttemptId = attemptId
        val now = Instant.now()
        jdbc.transaction {
            acquire(TicketLockRank.EFFECT)
            TicketPaymentOperations.insert {
                it[TicketPaymentOperations.provider] = authorizationProvider
                it[TicketPaymentOperations.operationId] = authorizationOperationId
                it[TicketPaymentOperations.attemptId] = purchaseAttemptId
                it[operationKind] = "authorize"
                it[status] = "pending"
                it[nextReconcileAt] = now
                it[claimRevision] = 0
                it[revision] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    /** PostgreSQL-specific EXPLAIN is intentionally isolated from normal CRUD. */
    fun explainDue(): List<String> =
        jdbc.transaction {
            exposed.exec("SET LOCAL enable_seqscan = off")
            exposed.exec(
                """
                EXPLAIN SELECT id FROM ticket_payment_operations
                WHERE status = 'pending' AND next_reconcile_at <= CURRENT_TIMESTAMP
                ORDER BY next_reconcile_at, id LIMIT 50
                """.trimIndent(),
                explicitStatementType = StatementType.SELECT,
            ) { result -> buildList { while (result.next()) add(result.getString(1)) } } ?: emptyList()
        }
}
