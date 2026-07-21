package io.bluetape4k.workshop.commerce.ticket.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Exposed mapping for the grade-level inventory authority. */
object TicketInventoryTable : Table("ticket_inventory") {
    val saleId = javaUUID("sale_id")
    val grade = varchar("grade", 32)
    val totalQuantity = integer("total_quantity")
    val heldQuantity = integer("held_quantity")
    val soldQuantity = integer("sold_quantity")
    val revision = long("revision")

    override val primaryKey = PrimaryKey(saleId, grade)
}

/** Exposed mapping for durable USER/IP active-attempt guards. */
object TicketActiveIdentityGuardTable : Table("ticket_active_identity_guards") {
    val saleId = javaUUID("sale_id")
    val identityKind = enumerationByName<IdentityKind>("identity_kind", 8)
    val identitySubjectId = javaUUID("identity_subject_id")
    val activeAttemptId = javaUUID("active_attempt_id")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(saleId, identityKind, identitySubjectId)
}

/** Exposed mapping for the canonical waiting-room queue. */
object TicketWaitingRoomEntryTable : Table("ticket_waiting_room_entries") {
    val id = long("id").autoIncrement()
    val entryId = javaUUID("entry_id")
    val saleId = javaUUID("sale_id")
    val userSubjectId = javaUUID("user_subject_id")
    val state = varchar("state", 24)
    val sequence = long("sequence")
    val revision = long("revision")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** Exposed mapping for fenced provider operations. */
object TicketPaymentOperationTable : Table("ticket_payment_operations") {
    val id = long("id").autoIncrement()
    val provider = varchar("provider", 32)
    val operationId = javaUUID("operation_id")
    val attemptId = javaUUID("attempt_id")
    val status = varchar("status", 32)
    val nextReconcileAt = timestamp("next_reconcile_at").nullable()
    val claimToken = javaUUID("claim_token").nullable()
    val claimRevision = long("claim_revision")
    val revision = long("revision")

    override val primaryKey = PrimaryKey(id)
}

/** Core Exposed mappings used by repository and transaction tests. */
val ticketAuthorityTables =
    arrayOf(
        TicketInventoryTable,
        TicketActiveIdentityGuardTable,
        TicketWaitingRoomEntryTable,
        TicketPaymentOperationTable,
    )
