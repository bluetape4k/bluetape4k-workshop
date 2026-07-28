package io.bluetape4k.workshop.commerce.ticket.persistence

import java.io.Serializable
import java.util.UUID

/** sale별로 독립적으로 보호되는 안정적인 identity category입니다. */
enum class IdentityKind { USER, IP }

/** lock이 적용된 inventory projection입니다. */
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

/** database sequence와 id로 정렬되는 canonical waiting-room row입니다. */
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
