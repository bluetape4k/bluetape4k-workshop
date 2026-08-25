package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.idgenerators.uuid.Uuid
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** UUID PK와 audit metadata를 함께 보존하는 plan aggregate record입니다. */
data class ShiftCoverageAggregateRecord(
    val id: UUID = Uuid.V7.nextId(),
    val siteId: String,
    val planId: String,
    val revision: Long,
    val snapshotDigest: String,
    val payload: String,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable, Serializable {
    init {
        require(siteId.isNotBlank() && planId.isNotBlank()) { "plan aggregate scope must not be blank" }
        require(revision >= 0L) { "plan revision must be non-negative" }
        require(snapshotDigest.matches(Regex("[0-9a-f]{64}"))) { "snapshot digest must be lowercase SHA-256" }
    }
    companion object { private const val serialVersionUID: Long = 1L }
}
