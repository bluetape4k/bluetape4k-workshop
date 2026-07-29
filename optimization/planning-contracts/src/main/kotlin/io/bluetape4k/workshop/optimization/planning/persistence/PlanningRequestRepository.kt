package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.jdbc.repository.UUIDAuditableJdbcRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.springframework.stereotype.Repository

@Repository
internal class PlanningRequestRepository:
    UUIDAuditableJdbcRepository<PlanningRequestRecord, PlanningRequestTable> {

    override val table = PlanningRequestTable

    override fun extractId(entity: PlanningRequestRecord) = entity.id

    override fun ResultRow.toEntity() = PlanningRequestRecord(
        id = this[PlanningRequestTable.id].value,
        aggregateId = this[PlanningRequestTable.aggregateId],
        aggregateVersion = this[PlanningRequestTable.aggregateVersion],
        datasetId = this[PlanningRequestTable.datasetId],
        parentRevision = this[PlanningRequestTable.parentRevision],
        acceptedRevision = this[PlanningRequestTable.acceptedRevision],
        status = this[PlanningRequestTable.status],
        scoreSummary = this[PlanningRequestTable.scoreSummary],
        redactedExplanation = this[PlanningRequestTable.redactedExplanation],
        provider = this[PlanningRequestTable.provider],
        providerRequestId = this[PlanningRequestTable.providerRequestId],
        createdBy = this[PlanningRequestTable.createdBy],
        createdAt = this[PlanningRequestTable.createdAt],
        updatedBy = this[PlanningRequestTable.updatedBy],
        updatedAt = this[PlanningRequestTable.updatedAt],
    )

    fun save(record: PlanningRequestRecord): PlanningRequestRecord {
        PlanningRequestTable.insert {
            it[id] = record.id
            it[aggregateId] = record.aggregateId
            it[aggregateVersion] = record.aggregateVersion
            it[datasetId] = record.datasetId
            it[parentRevision] = record.parentRevision
            it[acceptedRevision] = record.acceptedRevision
            it[status] = record.status
            it[scoreSummary] = record.scoreSummary
            it[redactedExplanation] = record.redactedExplanation
            it[provider] = record.provider
            it[providerRequestId] = record.providerRequestId
        }
        return findById(record.id)
    }

    fun acceptIfNewer(
        requestId: java.util.UUID,
        providerRevision: Long,
        scoreSummary: String,
        redactedExplanation: String,
    ): Boolean = auditedUpdateAll(
        predicate = {
            (PlanningRequestTable.id eq requestId) and
                (PlanningRequestTable.acceptedRevision.isNull() or
                    (PlanningRequestTable.acceptedRevision less providerRevision))
        },
    ) {
        it[acceptedRevision] = providerRevision
        it[status] = io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus.SUCCEEDED
        it[PlanningRequestTable.scoreSummary] = scoreSummary.take(160)
        it[PlanningRequestTable.redactedExplanation] = redactedExplanation.take(500)
    } == 1

    fun markSubmitted(
        requestId: java.util.UUID,
        providerRequestId: String,
    ): Boolean = auditedUpdateById(requestId) {
        it[status] = io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus.SUBMITTED
        it[PlanningRequestTable.providerRequestId] = providerRequestId.take(200)
    } == 1
}
