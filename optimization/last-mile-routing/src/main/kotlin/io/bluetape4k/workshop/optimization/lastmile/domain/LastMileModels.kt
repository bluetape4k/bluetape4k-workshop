package io.bluetape4k.workshop.optimization.lastmile.domain

import java.time.Instant

enum class Priority {
    NORMAL,
    URGENT,
}

enum class JobStatus {
    OPEN,
    CANCELLED,
    NO_SHOW,
    COMPLETED,
}

enum class StopKind {
    PICKUP,
    DELIVERY,
}

enum class PlanState {
    PROPOSED,
    APPROVED,
    COMMITTED,
}

enum class LastMileUnassignedReason {
    MISSING_SKILL,
    CAPACITY,
    TIME_WINDOW,
    MATRIX_MISS,
    PIN_CONFLICT,
    CANCELLED,
    NO_SHOW,
    COMPLETED,
    PROVIDER_UNAVAILABLE,
}

data class TimeWindow(
    val start: Instant,
    val end: Instant,
) {
    init {
        require(!end.isBefore(start)) { "time window end must not precede start" }
    }

    fun contains(value: Instant): Boolean = !value.isBefore(start) && !value.isAfter(end)
}

data class StartedStop(
    val jobId: JobId,
    val kind: StopKind,
    val coordinateId: CoordinateId,
    val sequence: Int,
    val startedAt: Instant,
) {
    init {
        require(sequence >= 0) { "started stop sequence must be non-negative" }
    }
}

data class DeliveryJob(
    val jobId: JobId,
    val pickupCoordinateId: CoordinateId,
    val deliveryCoordinateId: CoordinateId,
    val demand: Int,
    val pickupWindow: TimeWindow,
    val deliveryWindow: TimeWindow,
    val requiredSkill: String? = null,
    val priority: Priority = Priority.NORMAL,
    val carrierVersion: CarrierVersion = CarrierVersion(0),
    val status: JobStatus = JobStatus.OPEN,
) {
    init {
        require(demand > 0) { "job demand must be positive" }
        require(requiredSkill == null || requiredSkill.matches(Regex("[A-Za-z0-9._-]{1,32}"))) {
            "required skill must be a bounded identifier"
        }
    }
}

data class Vehicle(
    val vehicleId: VehicleId,
    val driverId: DriverId,
    val depotCoordinateId: CoordinateId,
    val capacity: Int,
    val skills: Set<String> = emptySet(),
    val availableAt: Instant,
    val startedStop: StartedStop? = null,
) {
    init {
        require(capacity > 0) { "vehicle capacity must be positive" }
        require(skills.all { it.matches(Regex("[A-Za-z0-9._-]{1,32}")) }) {
            "vehicle skills must be bounded identifiers"
        }
    }
}

data class LastMileRouteStop(
    val jobId: JobId,
    val kind: StopKind,
    val coordinateId: CoordinateId,
    val sequence: Int,
    val eta: Instant,
    val loadAfter: Int,
    val pinned: Boolean,
) {
    init {
        require(sequence >= 0) { "route sequence must be non-negative" }
        require(loadAfter >= 0) { "route load must be non-negative" }
    }
}

data class LastMileRoute(
    val vehicleId: VehicleId,
    val stops: List<LastMileRouteStop>,
) {
    init {
        require(stops.size <= LastMileLimits.MAX_STOPS_PER_ROUTE) { "route stops exceed configured limit" }
        require(stops.map { it.sequence } == stops.map { it.sequence }.sorted()) {
            "route stops must be ordered by sequence"
        }
    }
}

data class LastMileUnassignedJob(
    val jobId: JobId,
    val reason: LastMileUnassignedReason,
)

data class LastMileScore(
    val hardScore: Long,
    val softScore: Long,
    val assignedJobs: Int,
    val unassignedJobs: Int,
) {
    init {
        require(assignedJobs >= 0 && unassignedJobs >= 0) { "score counts must be non-negative" }
    }
}

data class LastMilePlanProposal(
    val planId: LastMilePlanId,
    val planRevision: Long,
    val parentRevision: Long?,
    val requestGeneration: Long,
    val matrixRevision: Long,
    val providerRevision: ProviderRevision? = null,
    val carrierVersions: Map<JobId, CarrierVersion>,
    val routes: List<LastMileRoute>,
    val unassigned: List<LastMileUnassignedJob>,
    val score: LastMileScore,
    val state: PlanState = PlanState.PROPOSED,
) {
    init {
        require(planRevision >= 0L) { "plan revision must be non-negative" }
        require(parentRevision == null || parentRevision >= 0L) { "parent revision must be non-negative" }
        require(requestGeneration >= 0L) { "request generation must be non-negative" }
        require(matrixRevision >= 0L) { "matrix revision must be non-negative" }
    }
}

data class LastMilePlannerInput(
    val planId: LastMilePlanId,
    val jobs: List<DeliveryJob>,
    val vehicles: List<Vehicle>,
    val matrix: io.bluetape4k.workshop.optimization.lastmile.planner.TravelTimeMatrix,
    val planRevision: Long = 0L,
    val parentRevision: Long? = null,
    val requestGeneration: Long = 0L,
) {
    init {
        require(jobs.size <= LastMileLimits.MAX_JOBS) { "jobs exceed configured limit" }
        require(vehicles.size <= LastMileLimits.MAX_VEHICLES) { "vehicles exceed configured limit" }
        require(planRevision >= 0L) { "plan revision must be non-negative" }
        require(parentRevision == null || parentRevision >= 0L) { "parent revision must be non-negative" }
        require(requestGeneration >= 0L) { "request generation must be non-negative" }
    }
}
