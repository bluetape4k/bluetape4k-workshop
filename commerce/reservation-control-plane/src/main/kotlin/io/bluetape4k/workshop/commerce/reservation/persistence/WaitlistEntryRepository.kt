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
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * resource별 waitlist의 FIFO sequence와 lifecycle state를 저장합니다.
 *
 * sequence allocation은 caller가 이미 resource lock을 보유한다고 가정합니다.
 * transition update는 owner와 revision을 계속 확인하므로 retry가 같은 entry를 두 번 advance하지 못합니다.
 */
@Repository
internal class WaitlistEntryRepository : LongAuditableJdbcRepository<WaitlistEntryRecord, WaitlistEntryTable> {
    override val table = WaitlistEntryTable

    override fun extractId(entity: WaitlistEntryRecord): Long = entity.id

    override fun ResultRow.toEntity() =
        WaitlistEntryRecord(
            id = this[table.id].value,
            resourceId = this[table.resourceId].value,
            ownerDigest = this[table.ownerDigest],
            state = this[table.state],
            sequence = this[table.sequence],
            revision = this[table.revision],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    /**
     * caller는 같은 resource에 대한 join을 직렬화해야 합니다(보통 resource row lock으로 수행).
     * 이렇게 해야 FIFO sequence allocation과 insert가 caller의 PostgreSQL transaction 안에 머뭅니다.
     */
    fun join(
        resourceId: Long,
        ownerDigest: String,
    ): WaitlistEntryRecord {
        require(ownerDigest.length == OWNER_DIGEST_LENGTH) { "owner digest must be 64 characters" }
        val nextSequence =
            table
                .selectAll()
                .where { table.resourceId eq resourceId }
                .orderBy(table.sequence to SortOrder.DESC, table.id to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(table.sequence)
                ?.plus(1)
                ?: 1L
        val id =
            table
                .insertAndGetId {
                    it[table.resourceId] = resourceId
                    it[table.ownerDigest] = ownerDigest
                    it[table.state] = WaitlistState.WAITING
                    it[table.sequence] = nextSequence
                }.value
        log.debug { "waitlist_entry_joined entryId=$id resourceId=$resourceId sequence=$nextSequence" }
        return findById(id)
    }

    fun snapshot(
        id: Long,
        ownerDigest: String,
    ): WaitlistEntryRecord? =
        table
            .selectAll()
            .where { (table.id eq id) and (table.ownerDigest eq ownerDigest) }
            .singleOrNull()
            ?.let { with(this) { it.toEntity() } }

    fun snapshots(resourceId: Long): List<WaitlistEntryRecord> =
        table
            .selectAll()
            .where { table.resourceId eq resourceId }
            .orderBy(table.sequence to SortOrder.ASC, table.id to SortOrder.ASC)
            .map { with(this) { it.toEntity() } }

    /** caller가 resource lock을 보유하는 동안 sequence와 id 기준으로 안정적인 FIFO head를 선택합니다. */
    fun oldestWaiting(resourceId: Long): WaitlistEntryRecord? =
        table
            .selectAll()
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
        val applied =
            auditedUpdateAll({
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
