package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.domain.WaitlistState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant

internal data class WaitlistEntryRecord(
    val id: Long,
    val resourceId: Long,
    val ownerDigest: String,
    val state: WaitlistState,
    val sequence: Long,
    val revision: Long,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Repository
internal class WaitlistEntryRepository : LongAuditableJdbcRepository<WaitlistEntryRecord, WaitlistEntryTable> {
    override val table = WaitlistEntryTable

    override fun extractId(entity: WaitlistEntryRecord): Long = entity.id

    override fun ResultRow.toEntity() = WaitlistEntryRecord(
        id = this[table.id].value,
        resourceId = this[table.resourceId].value,
        ownerDigest = this[table.ownerDigest],
        state = this[table.state],
        sequence = this[table.sequence],
        revision = this[table.revision],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt],
    )

    /**
     * The caller must serialize joins for the same resource (normally by locking the resource row).
     * This keeps FIFO sequence allocation and the insert in the caller's PostgreSQL transaction.
     */
    fun join(resourceId: Long, ownerDigest: String): WaitlistEntryRecord {
        require(ownerDigest.length == OWNER_DIGEST_LENGTH) { "owner digest must be 64 characters" }
        val nextSequence = table.selectAll()
            .where { table.resourceId eq resourceId }
            .orderBy(table.sequence to SortOrder.DESC, table.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(table.sequence)
            ?.plus(1)
            ?: 1L
        val id = table.insertAndGetId {
            it[table.resourceId] = resourceId
            it[table.ownerDigest] = ownerDigest
            it[table.state] = WaitlistState.WAITING
            it[table.sequence] = nextSequence
        }.value
        log.debug { "waitlist_entry_joined entryId=$id resourceId=$resourceId sequence=$nextSequence" }
        return findById(id)
    }

    fun snapshot(id: Long, ownerDigest: String): WaitlistEntryRecord? = table.selectAll()
        .where { (table.id eq id) and (table.ownerDigest eq ownerDigest) }
        .singleOrNull()
        ?.let { with(this) { it.toEntity() } }

    fun snapshots(resourceId: Long): List<WaitlistEntryRecord> = table.selectAll()
        .where { table.resourceId eq resourceId }
        .orderBy(table.sequence to SortOrder.ASC, table.id to SortOrder.ASC)
        .map { with(this) { it.toEntity() } }

    fun oldestWaiting(resourceId: Long): WaitlistEntryRecord? = table.selectAll()
        .where { (table.resourceId eq resourceId) and (table.state eq WaitlistState.WAITING) }
        .orderBy(table.sequence to SortOrder.ASC, table.id to SortOrder.ASC)
        .limit(1)
        .singleOrNull()
        ?.let { with(this) { it.toEntity() } }

    fun transition(
        id: Long,
        ownerDigest: String,
        expectedRevision: Long,
        from: WaitlistState,
        to: WaitlistState,
    ): Boolean {
        val applied = auditedUpdateAll({
            (table.id eq id) and
                (table.ownerDigest eq ownerDigest) and
                (table.revision eq expectedRevision) and
                (table.state eq from)
        }) {
            it[state] = to
            it[revision] = expectedRevision + 1
        } == 1
        log.debug {
            "waitlist_entry_transition entryId=$id expectedRevision=$expectedRevision from=$from to=$to applied=$applied"
        }
        return applied
    }

    companion object : KLogging() {
        private const val OWNER_DIGEST_LENGTH = 64
    }
}
