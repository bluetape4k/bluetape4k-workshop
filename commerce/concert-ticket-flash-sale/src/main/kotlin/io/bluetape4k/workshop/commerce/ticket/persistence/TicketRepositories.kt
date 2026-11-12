package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.*

/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 PostgreSQL inventory authority입니다. */
class TicketInventoryRepository(
    private val executor: TicketJdbcExecutor,
): TicketExposedJdbcRepository<TicketInventoryEntity, Long>(TicketInventoryEntity::class) {

    fun get(saleId: UUID, grade: String): InventoryRecord =
        executor.transaction {
            findAll { (TicketInventories.saleId eq saleId) and (TicketInventories.grade eq grade) }
                .singleOrNull()
                ?.toRecord()
                ?: error("inventory not found")
        }

    fun forceQuantities(saleId: UUID, grade: String, held: Int, sold: Int) {
        executor.transaction {
            acquire(TicketLockRank.INVENTORY)
            val whereOp = (TicketInventories.saleId eq saleId) and (TicketInventories.grade eq grade)
            val updated = TicketInventories.update({ whereOp }) {
                it[heldQuantity] = held
                it[soldQuantity] = sold
                it[revision] = revision + 1
            }
            check(updated == 1) { "inventory not found" }
        }
    }

    fun lock(transaction: TicketJdbcTransaction, saleId: UUID, grade: String): InventoryRecord {
        return with(transaction) {
            acquire(TicketLockRank.INVENTORY)

            TicketInventories
                .selectAll()
                .where { (TicketInventories.saleId eq saleId) and (TicketInventories.grade eq grade) }
                .forUpdate()
                .singleOrNull()
                ?.toInventoryRecord()
                ?: error("inventory not found")
        }
    }
}

/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 durable USER/IP guard authority입니다. */
class TicketIdentityGuardRepository(
    private val jdbc: TicketJdbcExecutor,
): TicketExposedJdbcRepository<TicketActiveIdentityGuardEntity, Long>(TicketActiveIdentityGuardEntity::class) {

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

/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 durable FIFO queue입니다. */
class TicketWaitingRoomRepository(
    private val jdbc: TicketJdbcExecutor,
): TicketExposedJdbcRepository<TicketWaitingRoomEntryEntity, Long>(TicketWaitingRoomEntryEntity::class) {
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
            getClaimBatchQuery(saleId, limit)
                .map { row -> row.toWaitingEntryRecord() }
        }

    /** PostgreSQL-specific EXPLAIN은 normal CRUD에서 의도적으로 격리합니다. */
    fun explainClaim(saleId: UUID): List<String> {
        return jdbc.transaction {
            transaction.exec("SET LOCAL enable_seqscan = off")

            val prepared = getClaimBatchQuery(saleId).prepareSQL(this.transaction, false)
            transaction.exec(stmt = "EXPLAIN $prepared", explicitStatementType = StatementType.SELECT) { result ->
                buildList {
                    while (result.next()) {
                        add(result.getString(1))
                    }
                }
            } ?: emptyList()
        }
    }

    @Suppress("UnusedReceiverParameter")
    private fun TicketJdbcTransaction.getClaimBatchQuery(saleId: UUID, limit: Int = 50): Query {
        return TicketWaitingRoomEntries
            .selectAll()
            .where { TicketWaitingRoomEntries.saleId eq saleId }
            .andWhere { TicketWaitingRoomEntries.state eq "waiting" }
            .orderBy(
                TicketWaitingRoomEntries.sequence to SortOrder.ASC,
                TicketWaitingRoomEntries.id to SortOrder.ASC
            )
            .limit(limit)
            .forUpdate(
                ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED),
            )
    }
}

/** Bluetape4k [TicketExposedJdbcRepository]로 뒷받침되는 payment-operation ledger입니다. */
class TicketPaymentOperationRepository(
    private val jdbc: TicketJdbcExecutor,
): TicketExposedJdbcRepository<TicketPaymentOperationEntity, Long>(TicketPaymentOperationEntity::class) {

    fun insertAuthorization(provider: String, operationId: UUID, attemptId: UUID) {
        val now = Instant.now()

        jdbc.transaction {
            acquire(TicketLockRank.EFFECT)
            TicketPaymentOperations.insert {
                it[TicketPaymentOperations.provider] = provider
                it[TicketPaymentOperations.operationId] = operationId
                it[TicketPaymentOperations.attemptId] = attemptId
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

    /** PostgreSQL-specific EXPLAIN은 normal CRUD에서 의도적으로 격리합니다. */
    @Suppress("SqlResolve")
    fun explainDue(): List<String> {
        return jdbc.transaction {
            transaction.exec("SET LOCAL enable_seqscan = off")
            transaction.exec("EXPLAIN ${getPendingId()}", explicitStatementType = StatementType.SELECT) { result ->
                buildList {
                    while (result.next()) {
                        add(result.getString(1))
                    }
                }
            } ?: emptyList()
        }
    }

    fun TicketJdbcTransaction.getPendingId(limit: Int = 50): String {
        val query = TicketPaymentOperations
            .select(TicketPaymentOperations.id)
            .where { TicketPaymentOperations.status eq "pending" }
            .andWhere { TicketPaymentOperations.nextReconcileAt lessEq CurrentTimestamp }
            .orderBy(
                TicketPaymentOperations.nextReconcileAt to SortOrder.ASC,
                TicketPaymentOperations.id to SortOrder.ASC
            )
            .limit(limit)

        return query.prepareSQL(this.transaction, false)
    }
}
