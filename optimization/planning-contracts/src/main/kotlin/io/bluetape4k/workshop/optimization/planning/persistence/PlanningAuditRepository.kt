package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.springframework.stereotype.Repository

@Repository
internal class PlanningAuditRepository: LongJdbcRepository<PlanningAuditRecord> {

    override val table = PlanningAuditTable

    override fun extractId(entity: PlanningAuditRecord) = entity.id

    override fun ResultRow.toEntity() = PlanningAuditRecord(
        id = this[PlanningAuditTable.id].value,
        planningRequestId = this[PlanningAuditTable.planningRequestId],
        callbackEventId = this[PlanningAuditTable.callbackEventId],
        aggregateVersion = this[PlanningAuditTable.aggregateVersion],
        providerRevision = this[PlanningAuditTable.providerRevision],
        status = this[PlanningAuditTable.status],
        scoreSummary = this[PlanningAuditTable.scoreSummary],
        redactedExplanation = this[PlanningAuditTable.redactedExplanation],
        decision = this[PlanningAuditTable.decision],
        createdAt = this[PlanningAuditTable.createdAt],
    )

    fun append(record: PlanningAuditRecord): PlanningAuditRecord {
        val id = PlanningAuditTable.insertAndGetId {
            it[planningRequestId] = record.planningRequestId
            it[callbackEventId] = record.callbackEventId
            it[aggregateVersion] = record.aggregateVersion
            it[providerRevision] = record.providerRevision
            it[status] = record.status
            it[scoreSummary] = record.scoreSummary
            it[redactedExplanation] = record.redactedExplanation
            it[decision] = record.decision
        }
        return findById(id.value)
    }
}
