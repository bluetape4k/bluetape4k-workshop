package io.bluetape4k.workshop.commerce.metering.eventsourcing.projection

import java.time.Instant
import java.util.UUID

enum class ProjectionGenerationState {
    ACTIVE,
    BUILDING,
    FAILED,
    RETIRED,
}

object ProjectionGenerationTransitions {
    fun allows(from: ProjectionGenerationState, to: ProjectionGenerationState): Boolean = when (from) {
        ProjectionGenerationState.ACTIVE -> to == ProjectionGenerationState.RETIRED
        ProjectionGenerationState.BUILDING ->
            to == ProjectionGenerationState.ACTIVE || to == ProjectionGenerationState.FAILED
        ProjectionGenerationState.FAILED -> to == ProjectionGenerationState.RETIRED
        ProjectionGenerationState.RETIRED -> to == ProjectionGenerationState.ACTIVE
    }
}

data class ProjectionGeneration(
    val projectionName: String,
    val generation: Int,
    val state: ProjectionGenerationState,
    val checkpoint: Long,
    val highWatermark: Long,
    val ownerToken: UUID?,
    val leaseUntil: Instant?,
    val failedPosition: Long?,
    val failureDigest: String?,
)

data class ProjectionLease(
    val projectionName: String,
    val generation: Int,
    val ownerToken: UUID,
    val leaseUntil: Instant,
)

enum class ProjectionApplyResult {
    APPLIED,
    ALREADY_APPLIED,
}

class StaleProjectionOwnerException(name: String, generation: Int) :
    IllegalStateException("stale_projection_owner:$name:$generation")
