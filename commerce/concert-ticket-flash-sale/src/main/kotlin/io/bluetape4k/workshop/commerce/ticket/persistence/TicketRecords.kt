package io.bluetape4k.workshop.commerce.ticket.persistence

import java.io.Serializable
import java.util.UUID

/** Stable identity categories guarded independently per sale. */
enum class IdentityKind { USER, IP }

/** Locked inventory projection. */
data class InventoryRecord(
    val saleId: UUID,
    val grade: String,
    val total: Int,
    val held: Int,
    val sold: Int,
    val revision: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Canonical waiting-room row ordered by database sequence and id. */
data class WaitingEntryRecord(
    val id: Long,
    val entryId: UUID,
    val saleId: UUID,
    val userSubjectId: UUID,
    val sequence: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
