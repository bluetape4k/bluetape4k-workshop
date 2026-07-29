package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

internal object PlanningOutboxTable: AuditableLongIdTable("planning_outbox") {
    val planningRequestId = javaUUID("planning_request_id").uniqueIndex()
    val payload = text("payload")
    val status = enumerationByName<PlanningOutboxStatus>("status", 24)
        .default(PlanningOutboxStatus.PENDING)
    val retryCount = integer("retry_count").default(0)
    val nextAttemptAt = timestamp("next_attempt_at")
    val claimedBy = varchar("claimed_by", 120).nullable()
    val claimedUntil = timestamp("claimed_until").nullable()
    val lastErrorCode = varchar("last_error_code", 80).nullable()
    val lastErrorSummary = varchar("last_error_summary", 240).nullable()
    val completedAt = timestamp("completed_at").nullable()
}
