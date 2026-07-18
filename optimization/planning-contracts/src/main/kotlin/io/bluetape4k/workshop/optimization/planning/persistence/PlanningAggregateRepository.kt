package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

@Repository
internal class PlanningAggregateRepository:
    LongAuditableJdbcRepository<PlanningAggregateRecord, PlanningAggregateTable> {

    override val table = PlanningAggregateTable

    override fun extractId(entity: PlanningAggregateRecord) = entity.id

    override fun ResultRow.toEntity() = PlanningAggregateRecord(
        id = this[PlanningAggregateTable.id].value,
        aggregateId = this[PlanningAggregateTable.aggregateId],
        version = this[PlanningAggregateTable.version],
        createdBy = this[PlanningAggregateTable.createdBy],
        createdAt = this[PlanningAggregateTable.createdAt],
        updatedBy = this[PlanningAggregateTable.updatedBy],
        updatedAt = this[PlanningAggregateTable.updatedAt],
    )

    fun save(record: PlanningAggregateRecord): PlanningAggregateRecord {
        val id = PlanningAggregateTable.insertAndGetId {
            it[aggregateId] = record.aggregateId
            it[version] = record.version
        }
        return findById(id.value)
    }

    fun versionMatches(aggregateId: String, expectedVersion: Long): Boolean =
        PlanningAggregateTable
            .selectAll()
            .where {
                (PlanningAggregateTable.aggregateId eq aggregateId) and
                    (PlanningAggregateTable.version eq expectedVersion)
            }
            .count() == 1L

    fun findByAggregateId(aggregateId: String): PlanningAggregateRecord? =
        PlanningAggregateTable
            .selectAll()
            .where { PlanningAggregateTable.aggregateId eq aggregateId }
            .singleOrNull()
            ?.let { row -> with(this) { row.toEntity() } }

    fun updateVersion(aggregateId: String, version: Long): Boolean = auditedUpdateAll(
        predicate = { PlanningAggregateTable.aggregateId eq aggregateId },
    ) {
        it[PlanningAggregateTable.version] = version
    } == 1
}
