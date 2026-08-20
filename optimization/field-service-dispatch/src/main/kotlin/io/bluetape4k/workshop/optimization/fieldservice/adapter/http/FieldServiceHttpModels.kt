package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanProposal
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitPriority
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import java.time.Duration
import java.time.Instant

data class CreateVisitRequest(
    @field:NotBlank val visitId: String,
    @field:NotBlank val coordinateId: String,
    @field:NotBlank val requiredSkill: String,
    val windowStart: Instant,
    val windowEnd: Instant,
    @field:PositiveOrZero val serviceDurationSeconds: Long,
    val priority: VisitPriority = VisitPriority.NORMAL,
) {
    init {
        require(windowStart < windowEnd) { "windowStart must precede windowEnd" }
        require(serviceDurationSeconds <= Duration.ofDays(1).seconds) { "service duration is too large" }
    }
}

data class PinVisitRequest(
    @field:NotBlank val workerId: String,
    @field:PositiveOrZero val routeOrder: Int,
)

data class TravelTimeUpdateRequest(
    @field:NotBlank val fromCoordinateId: String,
    @field:NotBlank val toCoordinateId: String,
    @field:PositiveOrZero val seconds: Long,
)

data class ReplanRequest(
    @field:NotBlank val planId: String,
    @field:NotBlank val datasetId: String,
)

data class PlanResponse(
    val plan: PlanProposal,
)

data class MutationResponse(
    val result: String,
)

data class ReplanResponse(
    val plan: PlanProposal,
)

data class FieldServiceErrorResponse(
    val status: Int,
    val code: String,
    val message: String,
)
