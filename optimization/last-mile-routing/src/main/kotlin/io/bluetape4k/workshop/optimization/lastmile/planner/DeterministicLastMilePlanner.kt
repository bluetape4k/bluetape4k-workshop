package io.bluetape4k.workshop.optimization.lastmile.planner

import io.bluetape4k.workshop.optimization.lastmile.domain.DeliveryJob
import io.bluetape4k.workshop.optimization.lastmile.domain.JobStatus
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanProposal
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlannerInput
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileRoute
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileRouteStop
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileScore
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileUnassignedJob
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileUnassignedReason
import io.bluetape4k.workshop.optimization.lastmile.domain.StopKind
import io.bluetape4k.workshop.optimization.lastmile.domain.Vehicle
import java.time.Duration
import java.time.Instant

/**
 * 고정 matrix만 사용하는 synthetic 라스트마일 planner입니다.
 * provider 호출이나 실제 교통 품질을 보장하지 않습니다.
 */
class DeterministicLastMilePlanner {

    fun plan(input: LastMilePlannerInput): LastMilePlanProposal {
        val sortedVehicles = input.vehicles.sortedBy { it.vehicleId.value }
        val routeBuilders = sortedVehicles.associateWith { vehicle ->
            RouteBuilder(vehicle, input)
        }.toMutableMap()
        val unassigned = mutableListOf<LastMileUnassignedJob>()

        input.jobs.sortedWith(
            compareByDescending<DeliveryJob> { it.priority }
                .thenBy { it.pickupWindow.start }
                .thenBy { it.jobId.value },
        ).forEach { job ->
            val terminal = when (job.status) {
                JobStatus.CANCELLED -> LastMileUnassignedReason.CANCELLED
                JobStatus.NO_SHOW -> LastMileUnassignedReason.NO_SHOW
                JobStatus.COMPLETED -> LastMileUnassignedReason.COMPLETED
                JobStatus.OPEN -> null
            }
            if (terminal != null) {
                unassigned += LastMileUnassignedJob(job.jobId, terminal)
                return@forEach
            }

            val candidates = sortedVehicles.mapNotNull { vehicle ->
                val builder = routeBuilders.getValue(vehicle)
                builder.candidate(job)
            }
            val winner = candidates.minWithOrNull(compareBy<Candidate> { it.finishAt }.thenBy { it.vehicle.vehicleId.value })
            if (winner == null) {
                unassigned += LastMileUnassignedJob(job.jobId, reasonFor(job, sortedVehicles, input.matrix))
            } else {
                routeBuilders.getValue(winner.vehicle).commit(winner)
            }
        }

        val routes = sortedVehicles.map { vehicle -> routeBuilders.getValue(vehicle).build() }
        val assignedJobs = input.jobs.count { it.status == JobStatus.OPEN && it.jobId !in unassigned.map { item -> item.jobId } }
        return LastMilePlanProposal(
            planId = input.planId,
            planRevision = input.planRevision,
            parentRevision = input.parentRevision,
            requestGeneration = input.requestGeneration,
            matrixRevision = input.matrix.revision,
            carrierVersions = input.jobs.associate { it.jobId to it.carrierVersion },
            routes = routes,
            unassigned = unassigned.sortedBy { it.jobId.value },
            score = LastMileScore(
                hardScore = assignedJobs.toLong() * 1_000L,
                softScore = -(routes.sumOf { it.stops.size }.toLong()),
                assignedJobs = assignedJobs,
                unassignedJobs = unassigned.size,
            ),
        )
    }

    private fun reasonFor(job: DeliveryJob, vehicles: List<Vehicle>, matrix: TravelTimeMatrix): LastMileUnassignedReason {
        if (vehicles.none { job.requiredSkill == null || job.requiredSkill in it.skills }) return LastMileUnassignedReason.MISSING_SKILL
        if (vehicles.none { it.capacity >= job.demand }) return LastMileUnassignedReason.CAPACITY
        val hasMatrix = vehicles.any { vehicle ->
            travel(matrix, vehicle.depotCoordinateId, job.pickupCoordinateId) != null &&
                travel(matrix, job.pickupCoordinateId, job.deliveryCoordinateId) != null
        }
        if (!hasMatrix) return LastMileUnassignedReason.MATRIX_MISS
        return LastMileUnassignedReason.TIME_WINDOW
    }

    private class RouteBuilder(
        private val vehicle: Vehicle,
        input: LastMilePlannerInput,
    ) {
        private val matrix = input.matrix
        private val stops = mutableListOf<LastMileRouteStop>()
        private var currentCoordinate = vehicle.depotCoordinateId
        private var availableAt = vehicle.availableAt
        private var currentLoad = 0

        init {
            vehicle.startedStop?.let { started ->
                stops += LastMileRouteStop(
                    jobId = started.jobId,
                    kind = started.kind,
                    coordinateId = started.coordinateId,
                    sequence = started.sequence,
                    eta = started.startedAt,
                    loadAfter = currentLoad,
                    pinned = true,
                )
                currentCoordinate = started.coordinateId
                availableAt = started.startedAt
            }
        }

        fun candidate(job: DeliveryJob): Candidate? {
            if (job.requiredSkill != null && job.requiredSkill !in vehicle.skills) return null
            if (job.demand > vehicle.capacity) return null
            val started = vehicle.startedStop
            if (started != null && started.jobId == job.jobId && started.kind == StopKind.DELIVERY) return Candidate(vehicle, emptyList(), availableAt)
            if (started != null && started.jobId == job.jobId && started.kind == StopKind.PICKUP) {
                val travelToDelivery = travel(matrix, currentCoordinate, job.deliveryCoordinateId) ?: return null
                val deliveryEta = availableAt.plusSeconds(travelToDelivery)
                if (!job.deliveryWindow.contains(deliveryEta)) return null
                return Candidate(
                    vehicle = vehicle,
                    stops = listOf(
                        LastMileRouteStop(job.jobId, StopKind.DELIVERY, job.deliveryCoordinateId, nextSequence(), deliveryEta, 0, false),
                    ),
                    finishAt = deliveryEta,
                )
            }
            val toPickup = travel(matrix, currentCoordinate, job.pickupCoordinateId) ?: return null
            val pickupArrival = availableAt.plusSeconds(toPickup)
            val pickupEta = maxOf(pickupArrival, job.pickupWindow.start)
            if (!job.pickupWindow.contains(pickupEta)) return null
            val toDelivery = travel(matrix, job.pickupCoordinateId, job.deliveryCoordinateId) ?: return null
            val deliveryEta = pickupEta.plusSeconds(toDelivery)
            if (!job.deliveryWindow.contains(deliveryEta)) return null
            val loadAfterPickup = currentLoad + job.demand
            if (loadAfterPickup > vehicle.capacity) return null
            return Candidate(
                vehicle = vehicle,
                stops = listOf(
                    LastMileRouteStop(job.jobId, StopKind.PICKUP, job.pickupCoordinateId, nextSequence(), pickupEta, loadAfterPickup, false),
                    LastMileRouteStop(job.jobId, StopKind.DELIVERY, job.deliveryCoordinateId, nextSequence() + 1, deliveryEta, currentLoad, false),
                ),
                finishAt = deliveryEta,
            )
        }

        fun commit(candidate: Candidate) {
            if (candidate.stops.isEmpty()) return
            stops += candidate.stops
            currentCoordinate = candidate.stops.last().coordinateId
            availableAt = candidate.finishAt
            currentLoad = candidate.stops.last().loadAfter
        }

        fun build(): LastMileRoute = LastMileRoute(vehicle.vehicleId, stops.sortedBy { it.sequence })

        private fun nextSequence(): Int = (stops.maxOfOrNull { it.sequence } ?: -1) + 1
    }

    private data class Candidate(
        val vehicle: Vehicle,
        val stops: List<LastMileRouteStop>,
        val finishAt: Instant,
    )

    private companion object {
        fun travel(matrix: TravelTimeMatrix, from: io.bluetape4k.workshop.optimization.lastmile.domain.CoordinateId, to: io.bluetape4k.workshop.optimization.lastmile.domain.CoordinateId): Long? =
            if (from == to) 0L else matrix.lookup(from, to)
    }
}
