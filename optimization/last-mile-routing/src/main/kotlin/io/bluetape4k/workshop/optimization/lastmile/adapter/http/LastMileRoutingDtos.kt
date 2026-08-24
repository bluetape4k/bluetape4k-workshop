package io.bluetape4k.workshop.optimization.lastmile.adapter.http

import io.bluetape4k.workshop.optimization.lastmile.domain.CarrierVersion
import io.bluetape4k.workshop.optimization.lastmile.domain.CoordinateId
import io.bluetape4k.workshop.optimization.lastmile.domain.DeliveryJob
import io.bluetape4k.workshop.optimization.lastmile.domain.DriverId
import io.bluetape4k.workshop.optimization.lastmile.domain.EventId
import io.bluetape4k.workshop.optimization.lastmile.domain.JobId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanProposal
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileRoute
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileRouteStop
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileScore
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileUnassignedJob
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileUnassignedReason
import io.bluetape4k.workshop.optimization.lastmile.domain.PlanState
import io.bluetape4k.workshop.optimization.lastmile.domain.Priority
import io.bluetape4k.workshop.optimization.lastmile.domain.ProviderRevision
import io.bluetape4k.workshop.optimization.lastmile.domain.StopKind
import io.bluetape4k.workshop.optimization.lastmile.domain.TimeWindow
import io.bluetape4k.workshop.optimization.lastmile.domain.Vehicle
import io.bluetape4k.workshop.optimization.lastmile.domain.VehicleId
import io.bluetape4k.workshop.optimization.lastmile.planner.CoordinatePair
import io.bluetape4k.workshop.optimization.lastmile.planner.TravelTimeMatrix
import io.bluetape4k.workshop.optimization.lastmile.application.LastMilePlanProjection
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileReconnectProjection
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileRouteProjection
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileStopProjection
import io.bluetape4k.workshop.optimization.lastmile.application.LastMileUnassignedProjection
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingCallback
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingResult
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

internal data class LastMileReplanHttpRequest(
    @field:NotBlank
    @field:Size(max = 96)
    val requestId: String = "",
    @field:NotBlank
    @field:Size(max = 64)
    val planId: String = "",
    @field:Min(0)
    val planRevision: Long = 0L,
    val parentRevision: Long? = null,
    @field:Min(0)
    val requestGeneration: Long = 0L,
    @field:Size(max = 128)
    @field:Valid
    val jobs: List<LastMileJobHttpRequest> = emptyList(),
    @field:Size(max = 32)
    @field:Valid
    val vehicles: List<LastMileVehicleHttpRequest> = emptyList(),
    @field:Valid
    val matrix: LastMileMatrixHttpRequest = LastMileMatrixHttpRequest(),
)

internal data class LastMileJobHttpRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val jobId: String = "",
    @field:NotBlank
    val pickupCoordinateId: String = "",
    @field:NotBlank
    val deliveryCoordinateId: String = "",
    @field:Min(1)
    val demand: Int = 1,
    val pickupWindowStart: Instant = Instant.EPOCH,
    val pickupWindowEnd: Instant = Instant.EPOCH,
    val deliveryWindowStart: Instant = Instant.EPOCH,
    val deliveryWindowEnd: Instant = Instant.EPOCH,
    @field:Size(max = 32)
    val requiredSkill: String? = null,
    val priority: String = Priority.NORMAL.name,
    @field:Min(0)
    val carrierVersion: Long = 0L,
)

internal data class LastMileVehicleHttpRequest(
    @field:NotBlank
    @field:Size(max = 64)
    val vehicleId: String = "",
    @field:NotBlank
    @field:Size(max = 64)
    val driverId: String = "",
    @field:NotBlank
    val depotCoordinateId: String = "",
    @field:Min(1)
    val capacity: Int = 1,
    @field:Size(max = 32)
    val skills: Set<String> = emptySet(),
    val availableAt: Instant = Instant.EPOCH,
    val startedStop: LastMileStartedStopHttpRequest? = null,
)

internal data class LastMileStartedStopHttpRequest(
    @field:NotBlank val jobId: String = "",
    val kind: String = StopKind.PICKUP.name,
    @field:NotBlank val coordinateId: String = "",
    @field:Min(0) val sequence: Int = 0,
    val startedAt: Instant = Instant.EPOCH,
)

internal data class LastMileMatrixHttpRequest(
    @field:Size(min = 1, max = 512)
    val coordinateIds: List<String> = emptyList(),
    @field:Min(0)
    val revision: Long = 0L,
    @field:Size(max = 4_096)
    @field:Valid
    val edges: List<LastMileMatrixEdgeHttpRequest> = emptyList(),
)

internal data class LastMileMatrixEdgeHttpRequest(
    @field:NotBlank val from: String = "",
    @field:NotBlank val to: String = "",
    @field:Min(0) val travelSeconds: Long = 0L,
)

internal data class LastMileApprovalHttpRequest(
    @field:Min(0)
    val planRevision: Long = 0L,
    @field:Min(0)
    val expectedMatrixRevision: Long = 0L,
    val expectedCarrierVersions: Map<String, Long> = emptyMap(),
)

internal data class LastMileEventHttpRequest(
    @field:NotBlank val type: String = "",
    @field:NotBlank @field:Size(max = 64) val aggregateId: String = "",
    @field:NotBlank @field:Size(max = 96) val eventKey: String = "",
    @field:Size(max = 64) val payload: Map<String, String> = emptyMap(),
    val occurredAt: Instant? = null,
)

internal data class LastMileCallbackHttpRequest(
    @field:NotBlank val provider: String = "",
    @field:NotBlank val eventId: String = "",
    @field:NotBlank val requestId: String = "",
    @field:Min(0) val providerRevision: Long = 0L,
    @field:NotBlank @field:Size(min = 64, max = 64) val payloadDigest: String = "",
    @field:Valid val result: LastMileRoutingResultHttpRequest = LastMileRoutingResultHttpRequest(),
)

internal data class LastMileRoutingResultHttpRequest(
    @field:NotBlank val provider: String = "",
    @field:NotBlank val requestId: String = "",
    @field:Valid val proposal: LastMileProposalHttpRequest = LastMileProposalHttpRequest(),
)

internal data class LastMileProposalHttpRequest(
    @field:NotBlank val planId: String = "",
    @field:Min(0) val planRevision: Long = 0L,
    val parentRevision: Long? = null,
    @field:Min(0) val requestGeneration: Long = 0L,
    @field:Min(0) val matrixRevision: Long = 0L,
    val providerRevision: Long? = null,
    val carrierVersions: Map<String, Long> = emptyMap(),
    val routes: List<LastMileRouteHttpRequest> = emptyList(),
    val unassigned: List<LastMileUnassignedHttpRequest> = emptyList(),
    val hardScore: Long = 0L,
    val softScore: Long = 0L,
    @field:Min(0) val assignedJobs: Int = 0,
    @field:Min(0) val unassignedJobs: Int = 0,
    val state: String = PlanState.PROPOSED.name,
)

internal data class LastMileRouteHttpRequest(
    @field:NotBlank val vehicleId: String = "",
    val stops: List<LastMileStopHttpRequest> = emptyList(),
)

internal data class LastMileStopHttpRequest(
    @field:NotBlank val jobId: String = "",
    val kind: String = StopKind.PICKUP.name,
    @field:NotBlank val coordinateId: String = "",
    @field:Min(0) val sequence: Int = 0,
    val eta: Instant = Instant.EPOCH,
    @field:Min(0) val loadAfter: Int = 0,
    val pinned: Boolean = false,
)

internal data class LastMileUnassignedHttpRequest(
    @field:NotBlank val jobId: String = "",
    val reason: String = "MATRIX_MISS",
)

internal data class LastMileEventHttpResponse(
    val eventId: String,
    val result: String,
    val requestGeneration: Long,
    val latestDigest: String,
)

internal data class LastMileCallbackHttpResponse(val decision: String)
internal data class LastMileReplanHttpResponse(val provider: String, val requestId: String, val requestGeneration: Long)
internal data class LastMileApprovalHttpResponse(val result: String)

internal fun LastMileReplanHttpRequest.toCommand() = io.bluetape4k.workshop.optimization.lastmile.application.LastMileReplanCommand(
    requestId = requestId,
    input = io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlannerInput(
        planId = LastMilePlanId(planId),
        planRevision = planRevision,
        parentRevision = parentRevision,
        requestGeneration = requestGeneration,
        jobs = jobs.map(LastMileJobHttpRequest::toDomain),
        vehicles = vehicles.map(LastMileVehicleHttpRequest::toDomain),
        matrix = matrix.toDomain(),
    ),
)

private fun LastMileJobHttpRequest.toDomain() = DeliveryJob(
    jobId = JobId(jobId),
    pickupCoordinateId = CoordinateId(pickupCoordinateId),
    deliveryCoordinateId = CoordinateId(deliveryCoordinateId),
    demand = demand,
    pickupWindow = TimeWindow(pickupWindowStart, pickupWindowEnd),
    deliveryWindow = TimeWindow(deliveryWindowStart, deliveryWindowEnd),
    requiredSkill = requiredSkill,
    priority = Priority.valueOf(priority),
    carrierVersion = CarrierVersion(carrierVersion),
)

private fun LastMileVehicleHttpRequest.toDomain() = Vehicle(
    vehicleId = VehicleId(vehicleId),
    driverId = DriverId(driverId),
    depotCoordinateId = CoordinateId(depotCoordinateId),
    capacity = capacity,
    skills = skills,
    availableAt = availableAt,
    startedStop = startedStop?.let {
        io.bluetape4k.workshop.optimization.lastmile.domain.StartedStop(
            jobId = JobId(it.jobId),
            kind = StopKind.valueOf(it.kind),
            coordinateId = CoordinateId(it.coordinateId),
            sequence = it.sequence,
            startedAt = it.startedAt,
        )
    },
)

private fun LastMileMatrixHttpRequest.toDomain(): TravelTimeMatrix {
    val coordinates = coordinateIds.map(::CoordinateId)
    val edgesByPair = edges.associate { edge ->
        CoordinatePair(CoordinateId(edge.from), CoordinateId(edge.to)) to edge.travelSeconds
    }
    require(edgesByPair.size == edges.size) { "matrix contains duplicate edges" }
    return TravelTimeMatrix(revision, coordinates.toSet(), edgesByPair)
}

internal fun LastMileCallbackHttpRequest.toDomain() = RoutingCallback(
    provider = provider,
    eventId = EventId(eventId),
    requestId = requestId,
    providerRevision = ProviderRevision(providerRevision),
    payloadDigest = payloadDigest,
    result = result.toDomain(providerRevision),
)

private fun LastMileRoutingResultHttpRequest.toDomain(providerRevision: Long) = RoutingResult(
    provider = provider,
    requestId = requestId,
    providerRevision = ProviderRevision(providerRevision),
    proposal = proposal.toDomain().copy(providerRevision = ProviderRevision(providerRevision)),
)

private fun LastMileProposalHttpRequest.toDomain() = LastMilePlanProposal(
    planId = LastMilePlanId(planId),
    planRevision = planRevision,
    parentRevision = parentRevision,
    requestGeneration = requestGeneration,
    matrixRevision = matrixRevision,
    providerRevision = providerRevision?.let(::ProviderRevision),
    carrierVersions = carrierVersions.mapKeys { JobId(it.key) }.mapValues { CarrierVersion(it.value) },
    routes = routes.map { route ->
        LastMileRoute(
            vehicleId = VehicleId(route.vehicleId),
            stops = route.stops.map { stop ->
                LastMileRouteStop(
                    jobId = JobId(stop.jobId),
                    kind = StopKind.valueOf(stop.kind),
                    coordinateId = CoordinateId(stop.coordinateId),
                    sequence = stop.sequence,
                    eta = stop.eta,
                    loadAfter = stop.loadAfter,
                    pinned = stop.pinned,
                )
            },
        )
    },
    unassigned = unassigned.map { LastMileUnassignedJob(JobId(it.jobId), LastMileUnassignedReason.valueOf(it.reason)) },
    score = LastMileScore(hardScore, softScore, assignedJobs, unassignedJobs),
    state = PlanState.valueOf(state),
)

internal fun LastMilePlanProjection.toResponse() = this
internal fun LastMileReconnectProjection.toResponse() = this
internal fun LastMileRouteProjection.toResponse() = this
internal fun LastMileStopProjection.toResponse() = this
internal fun LastMileUnassignedProjection.toResponse() = this
