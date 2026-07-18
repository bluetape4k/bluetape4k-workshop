package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.domain.OfferState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant

internal data class ReservationOfferRecord(
    val id: Long,
    val resourceId: Long,
    val entryId: Long,
    val ownerDigest: String,
    val state: OfferState,
    val revision: Long,
    val expiresAt: Instant,
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
internal class ReservationOfferRepository : LongAuditableJdbcRepository<ReservationOfferRecord, ReservationOfferTable> {
    override val table = ReservationOfferTable

    override fun extractId(entity: ReservationOfferRecord): Long = entity.id

    override fun ResultRow.toEntity() = ReservationOfferRecord(
        id = this[table.id].value,
        resourceId = this[table.resourceId].value,
        entryId = this[table.entryId].value,
        ownerDigest = this[table.ownerDigest],
        state = this[table.state],
        revision = this[table.revision],
        expiresAt = this[table.expiresAt],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt],
    )

    fun createActive(
        resourceId: Long,
        entryId: Long,
        ownerDigest: String,
        expiresAt: Instant,
    ): ReservationOfferRecord {
        val id = table.insertAndGetId {
            it[table.resourceId] = resourceId
            it[table.entryId] = entryId
            it[table.ownerDigest] = ownerDigest
            it[table.state] = OfferState.ACTIVE
            it[table.expiresAt] = expiresAt
        }.value
        log.debug { "reservation_offer_created offerId=$id entryId=$entryId resourceId=$resourceId" }
        return findById(id)
    }

    fun snapshot(id: Long, ownerDigest: String): ReservationOfferRecord? = table.selectAll()
        .where { (table.id eq id) and (table.ownerDigest eq ownerDigest) }
        .singleOrNull()
        ?.let { with(this) { it.toEntity() } }

    fun snapshots(resourceId: Long): List<ReservationOfferRecord> = table.selectAll()
        .where { table.resourceId eq resourceId }
        .orderBy(table.id to SortOrder.ASC)
        .map { with(this) { it.toEntity() } }

    fun activeForEntry(entryId: Long): ReservationOfferRecord? = table.selectAll()
        .where { (table.entryId eq entryId) and (table.state eq OfferState.ACTIVE) }
        .singleOrNull()
        ?.let { with(this) { it.toEntity() } }

    fun expiredResourceCandidates(now: Instant, limit: Int): List<ExpiredResourceCandidate> {
        require(limit in 1..32) { "limit must be between 1 and 32" }
        return table.selectAll()
            .where { (table.state eq OfferState.ACTIVE) and (table.expiresAt lessEq now) }
            .orderBy(table.expiresAt to SortOrder.ASC, table.id to SortOrder.ASC)
            .limit(limit * 4)
            .map { ExpiredResourceCandidate(it[table.resourceId].value, it[table.expiresAt]) }
            .distinctBy { it.resourceId }
            .take(limit)
    }

    fun expiredForResource(resourceId: Long, now: Instant): List<ReservationOfferRecord> = table.selectAll()
        .where {
            (table.resourceId eq resourceId) and
                (table.state eq OfferState.ACTIVE) and
                (table.expiresAt lessEq now)
        }
        .orderBy(table.expiresAt to SortOrder.ASC, table.id to SortOrder.ASC)
        .map { with(this) { it.toEntity() } }

    fun transition(
        id: Long,
        ownerDigest: String,
        expectedRevision: Long,
        from: OfferState,
        to: OfferState,
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
            "reservation_offer_transition offerId=$id expectedRevision=$expectedRevision from=$from to=$to applied=$applied"
        }
        return applied
    }

    companion object : KLogging()
}
