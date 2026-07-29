package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import java.io.Serializable
import java.time.Instant

internal data class PlanningAggregateRecord(
    val id: Long = 0L,
    val aggregateId: String,
    val version: Long,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
): Auditable, Serializable {
    init {
        require(aggregateId.isNotBlank()) { "aggregateId must not be blank" }
        require(version >= 0) { "aggregate version must not be negative" }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
