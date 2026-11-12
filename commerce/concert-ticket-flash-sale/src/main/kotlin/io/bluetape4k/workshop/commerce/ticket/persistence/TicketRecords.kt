package io.bluetape4k.workshop.commerce.ticket.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import java.io.Serializable
import java.util.*

/** sale별로 독립적으로 보호되는 안정적인 identity category입니다. */
enum class IdentityKind {
    USER,
    IP
}

/** lock이 적용된 inventory projection입니다. */
data class InventoryRecord(
    val saleId: UUID,
    val grade: String,
    val total: Int,
    val held: Int,
    val sold: Int,
    val revision: Long,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

fun ResultRow.toInventoryRecord(): InventoryRecord =
    InventoryRecord(
        saleId = this[TicketInventories.saleId],
        grade = this[TicketInventories.grade],
        total = this[TicketInventories.totalQuantity],
        held = this[TicketInventories.heldQuantity],
        sold = this[TicketInventories.soldQuantity],
        revision = this[TicketInventories.revision],
    )

/** database sequence와 id로 정렬되는 canonical waiting-room row입니다. */
data class WaitingEntryRecord(
    val id: Long,
    val entryId: UUID,
    val saleId: UUID,
    val userSubjectId: UUID,
    val sequence: Long,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

fun ResultRow.toWaitingEntryRecord(): WaitingEntryRecord =
    WaitingEntryRecord(
        id = this[TicketWaitingRoomEntries.id].value,
        entryId = this[TicketWaitingRoomEntries.entryId],
        saleId = this[TicketWaitingRoomEntries.saleId],
        userSubjectId = this[TicketWaitingRoomEntries.userSubjectId],
        sequence = this[TicketWaitingRoomEntries.sequence],
    )
