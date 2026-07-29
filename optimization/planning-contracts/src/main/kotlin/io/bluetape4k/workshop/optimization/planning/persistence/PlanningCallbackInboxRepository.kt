package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
internal class PlanningCallbackInboxRepository:
    LongAuditableJdbcRepository<PlanningCallbackInboxRecord, PlanningCallbackInboxTable> {

    override val table = PlanningCallbackInboxTable

    override fun extractId(entity: PlanningCallbackInboxRecord) = entity.id

    override fun ResultRow.toEntity() = PlanningCallbackInboxRecord(
        id = this[PlanningCallbackInboxTable.id].value,
        provider = this[PlanningCallbackInboxTable.provider],
        eventId = this[PlanningCallbackInboxTable.eventId],
        planningRequestId = this[PlanningCallbackInboxTable.planningRequestId],
        providerRevision = this[PlanningCallbackInboxTable.providerRevision],
        outcome = this[PlanningCallbackInboxTable.outcome],
        processedAt = this[PlanningCallbackInboxTable.processedAt],
        createdBy = this[PlanningCallbackInboxTable.createdBy],
        createdAt = this[PlanningCallbackInboxTable.createdAt],
        updatedBy = this[PlanningCallbackInboxTable.updatedBy],
        updatedAt = this[PlanningCallbackInboxTable.updatedAt],
    )

    fun insertIfAbsent(record: PlanningCallbackInboxRecord): Boolean {
        val statement = PlanningCallbackInboxTable.insertIgnore {
            it[provider] = record.provider
            it[eventId] = record.eventId
            it[planningRequestId] = record.planningRequestId
            it[providerRevision] = record.providerRevision
            it[outcome] = record.outcome
            it[processedAt] = record.processedAt
        }
        return statement.insertedCount > 0
    }

    fun markProcessed(
        provider: io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider,
        eventId: String,
        outcome: CallbackOutcome,
        processedAt: Instant,
    ): Boolean = auditedUpdateAll(
        predicate = {
            (PlanningCallbackInboxTable.provider eq provider) and
                (PlanningCallbackInboxTable.eventId eq eventId)
        },
    ) {
        it[PlanningCallbackInboxTable.outcome] = outcome
        it[PlanningCallbackInboxTable.processedAt] = processedAt
    } == 1
}
