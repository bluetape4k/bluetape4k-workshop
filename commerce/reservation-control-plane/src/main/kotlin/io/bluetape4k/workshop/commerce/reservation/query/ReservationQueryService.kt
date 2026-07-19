package io.bluetape4k.workshop.commerce.reservation.query

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.domain.ReservationTimePolicy
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** Projects authoritative resource counters with one observation timestamp and resource-local calendar time. */
@Service
internal class ReservationQueryService(
    private val resources: CapacityResourceRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun resources(): ResourceSnapshotResponse {
        val observedAt = clock.instant()
        val snapshots = resources.snapshots().map { it.toSnapshot(observedAt) }
        log.debug { "reservation_resources_queried count=${snapshots.size}" }
        return ResourceSnapshotResponse(observedAt, snapshots)
    }

    companion object : KLogging()
}

internal data class ResourceSnapshotResponse(
    val observedAt: Instant,
    val resources: List<ResourceSnapshot>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal data class ResourceSnapshot(
    val id: Long,
    val code: String,
    val state: String,
    val capacity: Int,
    val occupiedCount: Int,
    val availableCount: Int,
    val revision: Long,
    val policyVersion: Long,
    val timezone: String,
    val localObservedAt: OffsetDateTime,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private fun CapacityResourceRecord.toSnapshot(observedAt: Instant) =
    ResourceSnapshot(
        id = id,
        code = code,
        state = state.name,
        capacity = capacity,
        occupiedCount = occupiedCount,
        availableCount = capacity - occupiedCount,
        revision = revision,
        policyVersion = policyVersion,
        timezone = timezone,
        localObservedAt = ReservationTimePolicy.project(observedAt, ZoneId.of(timezone))
    )
