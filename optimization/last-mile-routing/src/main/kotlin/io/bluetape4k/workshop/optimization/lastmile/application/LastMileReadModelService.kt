package io.bluetape4k.workshop.optimization.lastmile.application

import io.bluetape4k.workshop.optimization.lastmile.domain.DriverId
import io.bluetape4k.workshop.optimization.lastmile.domain.DeliveryJob
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanProposal
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileRoute
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileRouteStop
import io.bluetape4k.workshop.optimization.lastmile.domain.StartedStop
import io.bluetape4k.workshop.optimization.lastmile.domain.Vehicle
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

internal data class LastMilePlanProjection(
    val planId: String,
    val revision: Long,
    val parentRevision: Long?,
    val state: String,
    val matrixRevision: Long,
    val providerRevision: Long?,
    val requestGeneration: Long,
    val revisionDiff: LastMileRevisionDiffProjection?,
    val score: LastMileScoreProjection,
    val routes: List<LastMileRouteProjection>,
    val unassigned: List<LastMileUnassignedProjection>,
    val carrierVersions: Map<String, Long>,
)

internal data class LastMileScoreProjection(
    val hardScore: Long,
    val softScore: Long,
    val assignedJobs: Int,
    val unassignedJobs: Int,
)

internal data class LastMileRouteProjection(
    val vehicleId: String,
    val depot: LastMilePointProjection?,
    val capacity: Int?,
    val skills: Set<String>,
    val polyline: List<LastMilePointProjection>,
    val stops: List<LastMileStopProjection>,
)

internal data class LastMilePointProjection(
    val coordinateId: String,
    val x: Int,
    val y: Int,
)

internal data class LastMileStopProjection(
    val jobId: String,
    val kind: String,
    val coordinateId: String,
    val sequence: Int,
    val eta: String,
    val loadAfter: Int,
    val pinned: Boolean,
    val pickupWindowStart: String?,
    val pickupWindowEnd: String?,
    val deliveryWindowStart: String?,
    val deliveryWindowEnd: String?,
    val requiredSkill: String?,
)

internal data class LastMileRevisionDiffProjection(
    val fromRevision: Long,
    val toRevision: Long,
)

internal data class LastMileUnassignedProjection(
    val jobId: String,
    val reason: String,
)

internal data class LastMileReconnectProjection(
    val driverId: String,
    val vehicleId: String?,
    val startedStop: StartedStopProjection?,
    val currentPlan: LastMilePlanProjection?,
)

internal data class StartedStopProjection(
    val jobId: String,
    val kind: String,
    val coordinateId: String,
    val sequence: Int,
    val startedAt: String,
)

@Service
internal class LastMileReadModelService(
    private val repository: LastMileRepository,
) {
    @Transactional(readOnly = true)
    fun plan(planId: LastMilePlanId): LastMilePlanProjection? {
        val jobs = repository.findJobs().associateBy { it.jobId }
        val vehicles = repository.findVehicles().associateBy { it.vehicleId }
        return repository.loadLatestProposal(planId)?.toProjection(jobs, vehicles)
    }

    @Transactional(readOnly = true)
    fun reconnect(driverId: DriverId): LastMileReconnectProjection {
        val vehicle = repository.findVehicles().firstOrNull { it.driverId == driverId }
        val jobs = repository.findJobs().associateBy { it.jobId }
        val currentPlan = vehicle?.let { current ->
            repository.loadLatestProposalForVehicle(current.vehicleId)?.toProjection(
                jobs = jobs,
                vehicles = mapOf(current.vehicleId to current),
            )
        }
        return LastMileReconnectProjection(
            driverId = driverId.value,
            vehicleId = vehicle?.vehicleId?.value,
            startedStop = vehicle?.startedStop?.toProjection(),
            currentPlan = currentPlan,
        )
    }

    private fun LastMilePlanProposal.toProjection(
        jobs: Map<io.bluetape4k.workshop.optimization.lastmile.domain.JobId, DeliveryJob>,
        vehicles: Map<io.bluetape4k.workshop.optimization.lastmile.domain.VehicleId, Vehicle>,
    ) = LastMilePlanProjection(
        planId = planId.value,
        revision = planRevision,
        parentRevision = parentRevision,
        state = state.name,
        matrixRevision = matrixRevision,
        providerRevision = providerRevision?.value,
        requestGeneration = requestGeneration,
        revisionDiff = parentRevision?.let { LastMileRevisionDiffProjection(it, planRevision) },
        score = LastMileScoreProjection(
            hardScore = score.hardScore,
            softScore = score.softScore,
            assignedJobs = score.assignedJobs,
            unassignedJobs = score.unassignedJobs,
        ),
        routes = routes.map { it.toProjection(jobs, vehicles) },
        unassigned = unassigned.map { LastMileUnassignedProjection(it.jobId.value, it.reason.name) },
        carrierVersions = carrierVersions.mapKeys { it.key.value }.mapValues { it.value.value },
    )

    private fun LastMileRoute.toProjection(
        jobs: Map<io.bluetape4k.workshop.optimization.lastmile.domain.JobId, DeliveryJob>,
        vehicles: Map<io.bluetape4k.workshop.optimization.lastmile.domain.VehicleId, Vehicle>,
    ) = LastMileRouteProjection(
        vehicleId = vehicleId.value,
        depot = vehicles[vehicleId]?.let { point(it.depotCoordinateId.value) },
        capacity = vehicles[vehicleId]?.capacity,
        skills = vehicles[vehicleId]?.skills ?: emptySet(),
        polyline = listOfNotNull(vehicles[vehicleId]?.depotCoordinateId?.value)
            .plus(stops.map { it.coordinateId.value })
            .distinct()
            .map(::point),
        stops = stops.map { it.toProjection(jobs[it.jobId]) },
    )

    private fun LastMileRouteStop.toProjection(job: DeliveryJob?) = LastMileStopProjection(
        jobId = jobId.value,
        kind = kind.name,
        coordinateId = coordinateId.value,
        sequence = sequence,
        eta = eta.toString(),
        loadAfter = loadAfter,
        pinned = pinned,
        pickupWindowStart = job?.pickupWindow?.start?.toString(),
        pickupWindowEnd = job?.pickupWindow?.end?.toString(),
        deliveryWindowStart = job?.deliveryWindow?.start?.toString(),
        deliveryWindowEnd = job?.deliveryWindow?.end?.toString(),
        requiredSkill = job?.requiredSkill,
    )

    private fun StartedStop.toProjection() = StartedStopProjection(
        jobId = jobId.value,
        kind = kind.name,
        coordinateId = coordinateId.value,
        sequence = sequence,
        startedAt = startedAt.toString(),
    )

    private fun point(coordinateId: String): LastMilePointProjection {
        val hash = coordinateId.hashCode()
        return LastMilePointProjection(
            coordinateId = coordinateId,
            x = (hash ushr 16) and 0x3ff,
            y = hash and 0x3ff,
        )
    }

}
