package io.bluetape4k.workshop.commerce.ticket.persistence

import java.sql.ResultSet
import java.util.UUID

/** PostgreSQL inventory authority accessed only through a bounded transaction. */
class TicketInventoryRepository(
    private val jdbc: TicketJdbcExecutor,
) {
    fun get(
        saleId: UUID,
        grade: String,
    ): InventoryRecord =
        jdbc.transaction {
            connection.prepareStatement(
                """
                SELECT sale_id, grade, total_quantity, held_quantity, sold_quantity, revision
                FROM ticket_inventory WHERE sale_id = ? AND grade = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, saleId)
                statement.setString(2, grade)
                statement.executeQuery().use { result ->
                    check(result.next()) { "inventory not found" }
                    result.toInventoryRecord()
                }
            }
        }

    fun forceQuantities(
        saleId: UUID,
        grade: String,
        held: Int,
        sold: Int,
    ) {
        jdbc.transaction {
            acquire(TicketLockRank.INVENTORY)
            connection.prepareStatement(
                "UPDATE ticket_inventory SET held_quantity = ?, sold_quantity = ?, revision = revision + 1 " +
                    "WHERE sale_id = ? AND grade = ?",
            ).use { statement ->
                statement.setInt(1, held)
                statement.setInt(2, sold)
                statement.setObject(3, saleId)
                statement.setString(4, grade)
                check(statement.executeUpdate() == 1) { "inventory not found" }
            }
        }
    }

    fun lock(
        transaction: TicketJdbcTransaction,
        saleId: UUID,
        grade: String,
    ): InventoryRecord =
        with(transaction) {
            acquire(TicketLockRank.INVENTORY)
            connection.prepareStatement(
                """
                SELECT sale_id, grade, total_quantity, held_quantity, sold_quantity, revision
                FROM ticket_inventory WHERE sale_id = ? AND grade = ? FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, saleId)
                statement.setString(2, grade)
                statement.executeQuery().use { result ->
                    check(result.next()) { "inventory not found" }
                    result.toInventoryRecord()
                }
            }
        }

    private fun ResultSet.toInventoryRecord(): InventoryRecord =
        InventoryRecord(
            saleId = getObject("sale_id", UUID::class.java),
            grade = getString("grade"),
            total = getInt("total_quantity"),
            held = getInt("held_quantity"),
            sold = getInt("sold_quantity"),
            revision = getLong("revision"),
        )
}

/** Durable USER/IP guard authority. */
class TicketIdentityGuardRepository(
    private val jdbc: TicketJdbcExecutor,
) {
    fun insert(
        saleId: UUID,
        kind: IdentityKind,
        subjectId: UUID,
        attemptId: UUID,
    ) {
        jdbc.transaction {
            acquire(if (kind == IdentityKind.USER) TicketLockRank.USER_GUARD else TicketLockRank.IP_GUARD)
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
    }
}

/** Durable FIFO queue using sequence then row id as canonical order. */
class TicketWaitingRoomRepository(
    private val jdbc: TicketJdbcExecutor,
) {
    fun join(
        saleId: UUID,
        userSubjectId: UUID,
        sequence: Long,
    ): UUID {
        val entryId = UUID.randomUUID()
        jdbc.transaction {
            connection.prepareStatement(
                """
                INSERT INTO ticket_waiting_room_entries(entry_id, sale_id, user_subject_id, sequence)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, entryId)
                statement.setObject(2, saleId)
                statement.setObject(3, userSubjectId)
                statement.setLong(4, sequence)
                statement.executeUpdate()
            }
        }
        return entryId
    }

    fun claimBatch(
        saleId: UUID,
        limit: Int,
    ): List<WaitingEntryRecord> =
        jdbc.transaction {
            connection.prepareStatement(
                """
                SELECT id, entry_id, sale_id, user_subject_id, sequence
                FROM ticket_waiting_room_entries
                WHERE sale_id = ? AND state = 'waiting'
                ORDER BY sequence, id
                LIMIT ? FOR UPDATE SKIP LOCKED
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, saleId)
                statement.setInt(2, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                WaitingEntryRecord(
                                    id = result.getLong("id"),
                                    entryId = result.getObject("entry_id", UUID::class.java),
                                    saleId = result.getObject("sale_id", UUID::class.java),
                                    userSubjectId = result.getObject("user_subject_id", UUID::class.java),
                                    sequence = result.getLong("sequence"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    fun explainClaim(saleId: UUID): List<String> =
        jdbc.transaction {
            connection.createStatement().use { it.execute("SET LOCAL enable_seqscan = off") }
            connection.prepareStatement(
                """
                EXPLAIN SELECT id FROM ticket_waiting_room_entries
                WHERE sale_id = ? AND state = 'waiting'
                ORDER BY sequence, id LIMIT 50 FOR UPDATE SKIP LOCKED
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, saleId)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
        }
}

/** Stable payment-operation ledger and reconciliation discovery index. */
class TicketPaymentOperationRepository(
    private val jdbc: TicketJdbcExecutor,
) {
    fun insertAuthorization(
        provider: String,
        operationId: UUID,
        attemptId: UUID,
    ) {
        jdbc.transaction {
            acquire(TicketLockRank.EFFECT)
            connection.prepareStatement(
                """
                INSERT INTO ticket_payment_operations(
                    provider, operation_id, attempt_id, operation_kind, status, next_reconcile_at
                ) VALUES (?, ?, ?, 'authorize', 'pending', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, provider)
                statement.setObject(2, operationId)
                statement.setObject(3, attemptId)
                statement.executeUpdate()
            }
        }
    }

    fun explainDue(): List<String> =
        jdbc.transaction {
            connection.createStatement().use { it.execute("SET LOCAL enable_seqscan = off") }
            connection.prepareStatement(
                """
                EXPLAIN SELECT id FROM ticket_payment_operations
                WHERE status = 'pending' AND next_reconcile_at <= CURRENT_TIMESTAMP
                ORDER BY next_reconcile_at, id LIMIT 50
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
        }
}
