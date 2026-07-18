package io.bluetape4k.workshop.optimization.planning.persistence

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

internal object PlanningAuditTable: LongIdTable("planning_audits") {
    val planningRequestId = javaUUID("planning_request_id")
    val callbackEventId = varchar("callback_event_id", 200)
    val aggregateVersion = long("aggregate_version")
    val providerRevision = long("provider_revision")
    val status = enumerationByName<io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus>("status", 24)
    val scoreSummary = varchar("score_summary", 160).nullable()
    val redactedExplanation = varchar("redacted_explanation", 500).nullable()
    val decision = enumerationByName<PlanningAuditDecision>("decision", 40)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}
