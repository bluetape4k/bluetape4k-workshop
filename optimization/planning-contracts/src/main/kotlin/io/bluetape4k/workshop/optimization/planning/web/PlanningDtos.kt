package io.bluetape4k.workshop.optimization.planning.web

import io.bluetape4k.workshop.optimization.planning.application.CreatePlanningRequest
import io.bluetape4k.workshop.optimization.planning.application.PlanningCallback
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.util.UUID

internal data class CreatePlanningRequestDto(
    @field:NotBlank
    @field:Size(max = 160)
    val aggregateId: String,
    @field:PositiveOrZero
    val aggregateVersion: Long,
    @field:NotBlank
    @field:Size(max = 160)
    val datasetId: String,
    @field:PositiveOrZero
    val parentRevision: Long? = null,
    val provider: PlanningProvider = PlanningProvider.FAKE,
) {
    fun toCommand() = CreatePlanningRequest(
        aggregateId = aggregateId,
        aggregateVersion = aggregateVersion,
        datasetId = datasetId,
        parentRevision = parentRevision,
        provider = provider,
    )
}

internal data class PlanningCallbackDto(
    @field:NotBlank
    @field:Size(max = 200)
    val eventId: String,
    val planningRequestId: UUID,
    @field:PositiveOrZero
    val providerRevision: Long,
    val status: PlanningStatus,
    @field:Size(max = 160)
    val scoreSummary: String,
    @field:Size(max = 20)
    val constraintExplanations: List<@Size(max = 240) String> = emptyList(),
) {
    fun toCommand(provider: PlanningProvider) = PlanningCallback(
        provider = provider,
        eventId = eventId,
        planningRequestId = planningRequestId,
        providerRevision = providerRevision,
        status = status,
        scoreSummary = scoreSummary,
        constraintExplanations = constraintExplanations,
    )
}

internal data class PlanningCreatedResponse(
    val id: UUID,
    val status: PlanningStatus,
)

internal data class ProcessedResponse(val processed: Int)

internal data class CallbackDecisionResponse(val decision: String)

internal data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
)
