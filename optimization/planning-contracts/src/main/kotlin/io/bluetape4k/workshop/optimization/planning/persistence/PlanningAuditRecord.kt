package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import java.io.Serializable
import java.time.Instant
import java.util.UUID

internal enum class PlanningAuditDecision {
    ACCEPTED,
    STALE_REVISION,
    AGGREGATE_CHANGED,
    PROVIDER_MISMATCH,
    REJECTED,
}

internal data class PlanningAuditRecord(
    val id: Long = 0L,
    val planningRequestId: UUID,
    val callbackEventId: String,
    val aggregateVersion: Long,
    val providerRevision: Long,
    val status: PlanningStatus,
    val scoreSummary: String? = null,
    val redactedExplanation: String? = null,
    val decision: PlanningAuditDecision,
    val createdAt: Instant? = null,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
