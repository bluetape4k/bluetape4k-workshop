package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.domain.ResourceState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

/**
 * Persists the capacity counter that acts as the reservation correctness boundary.
 *
 * Occupy and release operations combine state, revision, and capacity predicates in one SQL update
 * so callers never authorize capacity from a stale read.
 */
@Repository
internal class CapacityResourceRepository :
    LongAuditableJdbcRepository<CapacityResourceRecord, CapacityResourceTable> {
    override val table = CapacityResourceTable

    override fun extractId(entity: CapacityResourceRecord): Long = entity.id

    override fun ResultRow.toEntity() =
        CapacityResourceRecord(
            id = this[table.id].value,
            code = this[table.code],
            state = this[table.state],
            capacity = this[table.capacity],
            occupiedCount = this[table.occupiedCount],
            revision = this[table.revision],
            policyVersion = this[table.policyVersion],
            timezone = this[table.timezone],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun create(
        code: String,
        capacity: Int,
        policyVersion: Long,
    ): CapacityResourceRecord {
        require(capacity > 0) { "capacity must be positive" }
        val id =
            table
                .insertAndGetId {
                    it[table.code] = code.take(80)
                    it[table.capacity] = capacity
                    it[table.policyVersion] = policyVersion
                }.value
        log.debug { "capacity_resource_created resourceId=$id capacity=$capacity policyVersion=$policyVersion" }
        return findById(id)
    }

    fun tryOccupy(
        id: Long,
        expectedRevision: Long,
    ): Boolean {
        val applied =
            auditedUpdateAll({
                (table.id eq id) and
                    (table.state eq ResourceState.OPEN) and
                    (table.revision eq expectedRevision) and
                    (table.occupiedCount less table.capacity)
            }) {
                it[occupiedCount] = occupiedCount + 1
                it[revision] = expectedRevision + 1
            } == 1
        log.debug { "capacity_resource_occupy resourceId=$id expectedRevision=$expectedRevision applied=$applied" }
        return applied
    }

    fun release(
        id: Long,
        expectedRevision: Long,
    ): Boolean =
        auditedUpdateAll({
            (table.id eq id) and
                (table.revision eq expectedRevision) and
                (table.occupiedCount greater 0)
        }) {
            it[occupiedCount] = occupiedCount - 1
            it[revision] = expectedRevision + 1
        }.also { updated ->
            log.debug { "capacity_resource_release resourceId=$id expectedRevision=$expectedRevision updated=$updated" }
        } == 1

    fun snapshots(): List<CapacityResourceRecord> = table.selectAll().map { with(this) { it.toEntity() } }

    /** Acquires the canonical first lock for every transaction that may transfer capacity ownership. */
    fun findByIdForUpdate(id: Long): CapacityResourceRecord =
        table
            .selectAll()
            .where { table.id eq id }
            .forUpdate()
            .singleOrNull()
            ?.let { with(this) { it.toEntity() } }
            ?: throw NoSuchElementException("capacity resource not found")

    companion object : KLogging()
}
