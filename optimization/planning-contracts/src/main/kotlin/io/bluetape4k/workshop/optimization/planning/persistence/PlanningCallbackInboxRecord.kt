package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import java.io.Serializable
import java.time.Instant
import java.util.UUID

internal enum class CallbackOutcome {
    RECEIVED,
    ACCEPTED,
    STALE_REVISION,
    AGGREGATE_CHANGED,
    PROVIDER_MISMATCH,
    REJECTED,
}

internal data class PlanningCallbackInboxRecord(
    val id: Long = 0,
    val provider: PlanningProvider,
    val eventId: String,
    val planningRequestId: UUID,
    val providerRevision: Long,
    val outcome: CallbackOutcome,
    val processedAt: Instant? = null,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
): Auditable, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
