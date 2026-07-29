package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.time.Instant

/** owner, state, revision을 확인하는 hold mutation을 Exposed JDBC CAS update로 적용합니다. */
@Repository
internal class ReservationHoldRepository : LongAuditableJdbcRepository<ReservationHoldRecord, ReservationHoldTable> {
    override val table = ReservationHoldTable

    override fun extractId(entity: ReservationHoldRecord): Long = entity.id

    override fun ResultRow.toEntity() =
        ReservationHoldRecord(
            id = this[table.id].value,
            resourceId = this[table.resourceId].value,
            ownerDigest = this[table.ownerDigest],
            state = this[table.state],
            revision = this[table.revision],
            policyVersion = this[table.policyVersion],
            expiresAt = this[table.expiresAt],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun create(
        resourceId: Long,
        ownerDigest: String,
        policyVersion: Long,
        expiresAt: Instant,
    ): ReservationHoldRecord {
        val id =
            table
                .insertAndGetId {
                    it[table.resourceId] = resourceId
                    it[table.ownerDigest] = ownerDigest
                    it[table.state] = HoldState.HELD
                    it[table.policyVersion] = policyVersion
                    it[table.expiresAt] = expiresAt
                }.value
        log.debug { "reservation_hold_created holdId=$id resourceId=$resourceId policyVersion=$policyVersion" }
        return findById(id)
    }

    fun transition(
        id: Long,
        ownerDigest: String,
        expectedRevision: Long,
        from: HoldState,
        to: HoldState,
    ): Boolean =
        auditedUpdateAll({
            (table.id eq id) and
                (table.ownerDigest eq ownerDigest) and
                (table.revision eq expectedRevision) and
                (table.state eq from)
        }) {
            it[state] = to
            it[revision] = expectedRevision + 1
        } == 1

    fun extend(
        id: Long,
        ownerDigest: String,
        expectedRevision: Long,
        expectedExpiresAt: Instant,
        newExpiresAt: Instant,
    ): Boolean =
        auditedUpdateAll({
            (table.id eq id) and
                (table.ownerDigest eq ownerDigest) and
                (table.revision eq expectedRevision) and
                (table.state eq HoldState.HELD) and
                (table.expiresAt eq expectedExpiresAt)
        }) {
            it[expiresAt] = newExpiresAt
            it[revision] = expectedRevision + 1
        }.also { updated ->
            log.debug { "reservation_hold_extended holdId=$id expectedRevision=$expectedRevision updated=$updated" }
        } == 1

    /** bounded candidate를 반환합니다. 최종 global hold/offer ordering은 transaction service가 수행합니다. */
    fun expiredResourceCandidates(
        now: Instant,
        limit: Int,
    ): List<ExpiredResourceCandidate> {
        require(limit in 1..32) { "limit must be between 1 and 32" }
        return table
            .selectAll()
            .where { (table.state eq HoldState.HELD) and (table.expiresAt lessEq now) }
            .orderBy(table.expiresAt to SortOrder.ASC, table.id to SortOrder.ASC)
            .limit(limit * 4)
            .map { ExpiredResourceCandidate(it[table.resourceId].value, it[table.expiresAt]) }
            .distinctBy { it.resourceId }
            .take(limit)
    }

    fun expiredForResource(
        resourceId: Long,
        now: Instant,
    ): List<ReservationHoldRecord> =
        table
            .selectAll()
            .where {
                (table.resourceId eq resourceId) and
                    (table.state eq HoldState.HELD) and
                    (table.expiresAt lessEq now)
            }.orderBy(table.expiresAt to SortOrder.ASC, table.id to SortOrder.ASC)
            .map { with(this) { it.toEntity() } }

    companion object : KLogging()
}
