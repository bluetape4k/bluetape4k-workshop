package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.workshop.commerce.reservation.domain.HoldState
import io.bluetape4k.workshop.commerce.reservation.domain.ResourceState
import java.io.Serializable
import java.time.Instant

/** authoritative capacity row의 Exposed repository projection입니다. */
internal data class CapacityResourceRecord(
    val id: Long,
    val code: String,
    val state: ResourceState,
    val capacity: Int,
    val occupiedCount: Int,
    val revision: Long,
    val policyVersion: Long,
    val timezone: String,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** durable하고 revisioned된 hold의 Exposed repository projection입니다. */
internal data class ReservationHoldRecord(
    val id: Long,
    val resourceId: Long,
    val ownerDigest: String,
    val state: HoldState,
    val revision: Long,
    val policyVersion: Long,
    val expiresAt: Instant,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
