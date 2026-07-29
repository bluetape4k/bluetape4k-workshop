package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import java.io.Serializable
import java.time.Instant
import java.util.UUID

internal enum class PlanningOutboxStatus {
    PENDING,
    CLAIMED,
    COMPLETED,
    FAILED,
    DEAD_LETTER,
}

internal data class PlanningOutboxRecord(
    val id: Long = 0L,
    val planningRequestId: UUID,
    val payload: String,
    val status: PlanningOutboxStatus = PlanningOutboxStatus.PENDING,
    val retryCount: Int = 0,
    val nextAttemptAt: Instant,
    val claimedBy: String? = null,
    val claimedUntil: Instant? = null,
    val lastErrorCode: String? = null,
    val lastErrorSummary: String? = null,
    val completedAt: Instant? = null,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
): Auditable, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
