package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable

internal object PlanningAggregateTable: AuditableLongIdTable("planning_aggregates") {
    val aggregateId = varchar("aggregate_id", 160).uniqueIndex()
    val version = long("aggregate_version")
}
