package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.BillingReadModelEntry
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.NewBillingReadModelEntry
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.NewProjectionFailure
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionFailure
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionModelType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Repository
class BillingReadModelRepository :
    EventSourcingExposedJdbcRepository<BillingReadModelEntity, UUID>(BillingReadModelEntity::class.java) {

    fun append(entry: NewBillingReadModelEntry) {
        BillingReadModels.insert {
            it[id] = Uuid.V7.nextId()
            it[projectionName] = entry.projectionName
            it[generation] = entry.generation
            it[tenantId] = entry.tenantId
            it[modelType] = entry.modelType.name
            it[entryId] = entry.entryId
            it[eventType] = entry.eventType
            it[globalPosition] = entry.globalPosition
            it[quantity] = entry.quantity
            it[amount] = entry.amount
            it[currency] = entry.currency
            it[provenance] = entry.provenance
            it[occurredAt] = entry.occurredAt
        }
    }

    fun entries(projectionName: String, generation: Int, tenantId: String): List<BillingReadModelEntry> =
        BillingReadModels.selectAll()
            .where {
                (BillingReadModels.projectionName eq projectionName) and
                    (BillingReadModels.generation eq generation) and
                    (BillingReadModels.tenantId eq tenantId)
            }
            .orderBy(
                BillingReadModels.globalPosition to SortOrder.ASC,
                BillingReadModels.modelType to SortOrder.ASC,
            )
            .map { row ->
                BillingReadModelEntry(
                    modelType = ProjectionModelType.valueOf(row[BillingReadModels.modelType]),
                    entryId = row[BillingReadModels.entryId],
                    eventType = row[BillingReadModels.eventType],
                    globalPosition = row[BillingReadModels.globalPosition],
                    quantity = row[BillingReadModels.quantity],
                    amount = row[BillingReadModels.amount],
                    currency = row[BillingReadModels.currency],
                    provenance = row[BillingReadModels.provenance],
                    occurredAt = row[BillingReadModels.occurredAt],
                )
            }

    fun financialTotal(projectionName: String, generation: Int, tenantId: String): BigDecimal =
        entries(projectionName, generation, tenantId)
            .filter { entry ->
                entry.modelType == ProjectionModelType.LEDGER_DEBIT ||
                    entry.modelType == ProjectionModelType.LEDGER_CREDIT
            }
            .fold(BigDecimal.ZERO) { total, entry ->
                val amount = checkNotNull(entry.amount)
                if (entry.modelType == ProjectionModelType.LEDGER_CREDIT) total - amount else total + amount
            }
}

@Repository
class ProjectionFailureRepository :
    AppendOnlyEventSourcingExposedJdbcRepository<ProjectionFailureEntity, UUID>(ProjectionFailureEntity::class.java) {

    fun record(failure: NewProjectionFailure) {
        val inserted = ProjectionFailures.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[projectionName] = failure.projectionName
            it[generation] = failure.generation
            it[eventId] = failure.eventId
            it[eventType] = failure.eventType
            it[globalPosition] = failure.globalPosition
            it[errorDigest] = failure.errorDigest
            it[attemptCount] = 1
            it[firstFailedAt] = failure.failedAt
            it[lastFailedAt] = failure.failedAt
        }.insertedCount == 1
        if (!inserted) incrementAttempt(failure)
    }

    fun latest(projectionName: String, generation: Int): ProjectionFailure? =
        ProjectionFailures.selectAll()
            .where {
                (ProjectionFailures.projectionName eq projectionName) and
                    (ProjectionFailures.generation eq generation)
            }
            .orderBy(ProjectionFailures.globalPosition to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let { row ->
                ProjectionFailure(
                    eventId = row[ProjectionFailures.eventId],
                    eventType = row[ProjectionFailures.eventType],
                    globalPosition = row[ProjectionFailures.globalPosition],
                    errorDigest = row[ProjectionFailures.errorDigest],
                    attemptCount = row[ProjectionFailures.attemptCount],
                )
            }

    private fun incrementAttempt(failure: NewProjectionFailure) {
        val row = ProjectionFailures.selectAll()
            .where {
                (ProjectionFailures.projectionName eq failure.projectionName) and
                    (ProjectionFailures.generation eq failure.generation) and
                    (ProjectionFailures.eventId eq failure.eventId)
            }
            .forUpdate()
            .single()
        ProjectionFailures.update({ ProjectionFailures.id eq row[ProjectionFailures.id] }) {
            it[attemptCount] = row[ProjectionFailures.attemptCount] + 1
            it[ProjectionFailures.errorDigest] = failure.errorDigest
            it[lastFailedAt] = failure.failedAt
        }
    }
}
