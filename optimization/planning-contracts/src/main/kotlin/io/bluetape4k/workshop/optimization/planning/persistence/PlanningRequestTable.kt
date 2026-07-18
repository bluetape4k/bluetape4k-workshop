package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.core.auditable.AuditableUUIDTable
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus

internal object PlanningRequestTable: AuditableUUIDTable("planning_requests") {
    val aggregateId = varchar("aggregate_id", 128)
    val aggregateVersion = long("aggregate_version")
    val datasetId = varchar("dataset_id", 160)
    val parentRevision = long("parent_revision").nullable()
    val acceptedRevision = long("accepted_revision").nullable()
    val status = enumerationByName<PlanningStatus>("status", 32)
    val scoreSummary = varchar("score_summary", 160).nullable()
    val redactedExplanation = text("redacted_explanation").nullable()
    val provider = enumerationByName<PlanningProvider>("provider", 32)
    val providerRequestId = varchar("provider_request_id", 200).nullable()
}
