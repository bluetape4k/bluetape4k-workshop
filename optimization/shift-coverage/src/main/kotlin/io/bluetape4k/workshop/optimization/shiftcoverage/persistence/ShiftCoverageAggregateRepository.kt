package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.exposed.jdbc.repository.UUIDAuditableJdbcRepository
import io.bluetape4k.idgenerators.uuid.Uuid
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.util.UUID

/** plan/generation/audit aggregate CRUD를 Bluetape UUID repository 계약으로 유지합니다. */
@Repository
class ShiftCoverageAggregateRepository : UUIDAuditableJdbcRepository<ShiftCoverageAggregateRecord, ShiftCoveragePlansTable> {
    override val table = ShiftCoveragePlansTable

    override fun extractId(entity: ShiftCoverageAggregateRecord): UUID = entity.id

    override fun ResultRow.toEntity() = ShiftCoverageAggregateRecord(
        id = this[ShiftCoveragePlansTable.id].value,
        siteId = this[ShiftCoveragePlansTable.siteId],
        planId = this[ShiftCoveragePlansTable.planId],
        revision = this[ShiftCoveragePlansTable.revision],
        snapshotDigest = this[ShiftCoveragePlansTable.snapshotDigest],
        payload = this[ShiftCoveragePlansTable.payload],
        createdBy = this[ShiftCoveragePlansTable.createdBy],
        createdAt = this[ShiftCoveragePlansTable.createdAt],
        updatedBy = this[ShiftCoveragePlansTable.updatedBy],
        updatedAt = this[ShiftCoveragePlansTable.updatedAt],
    )

    fun save(record: ShiftCoverageAggregateRecord): ShiftCoverageAggregateRecord {
        ShiftCoveragePlansTable.insert { statement ->
            statement[id] = record.id
            statement[siteId] = record.siteId
            statement[planId] = record.planId
            statement[revision] = record.revision
            statement[snapshotDigest] = record.snapshotDigest
            statement[payload] = record.payload
        }
        return findById(record.id)
    }

    fun findByPlanRevision(siteId: String, planId: String, revision: Long): ShiftCoverageAggregateRecord? =
        ShiftCoveragePlansTable.selectAll().where {
            (ShiftCoveragePlansTable.siteId eq siteId) and
                (ShiftCoveragePlansTable.planId eq planId) and
                (ShiftCoveragePlansTable.revision eq revision)
        }.singleOrNull()?.let { with(this) { it.toEntity() } }

    fun nextId(): UUID = Uuid.V7.nextId()
}
