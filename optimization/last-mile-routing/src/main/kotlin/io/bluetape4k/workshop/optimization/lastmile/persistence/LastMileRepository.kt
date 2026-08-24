package io.bluetape4k.workshop.optimization.lastmile.persistence

import io.bluetape4k.workshop.optimization.lastmile.domain.CarrierVersion
import io.bluetape4k.workshop.optimization.lastmile.domain.CoordinateId
import io.bluetape4k.workshop.optimization.lastmile.domain.DeliveryJob
import io.bluetape4k.workshop.optimization.lastmile.domain.DriverId
import io.bluetape4k.workshop.optimization.lastmile.domain.EventDigestMatch
import io.bluetape4k.workshop.optimization.lastmile.domain.EventId
import io.bluetape4k.workshop.optimization.lastmile.domain.JobId
import io.bluetape4k.workshop.optimization.lastmile.domain.JobStatus
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileEvent
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileEventCanonicalizer
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
import io.bluetape4k.workshop.optimization.lastmile.domain.StartedStop
import io.bluetape4k.workshop.optimization.lastmile.domain.StopKind
import io.bluetape4k.workshop.optimization.lastmile.domain.TimeWindow
import io.bluetape4k.workshop.optimization.lastmile.domain.Vehicle
import org.springframework.stereotype.Component

enum class LastMileEventAppendResult {
    APPENDED,
    DUPLICATE,
    DIGEST_CONFLICT,
}

/**
 * bluetape4k-exposed repository들을 하나의 application-port로 조합합니다.
 * 호출자는 활성 Exposed transaction을 소유하고, 이 facade는 PostgreSQL 변경을
 * [LongAuditableJdbcRepository] 기반 adapter에 위임합니다.
 */
@Component
internal class LastMileRepository(
    private val jobs: LastMileJobRepository,
    private val vehicles: LastMileVehicleRepository,
    private val plans: LastMilePlanRepository,
    private val carriers: LastMilePlanCarrierRepository,
    private val stops: LastMilePlanStopRepository,
    private val unassigned: LastMileUnassignedRepository,
    private val events: LastMileEventRepository,
) {
    fun saveJob(job: DeliveryJob): JobId {
        jobs.save(job.toRecord())
        return job.jobId
    }

    fun saveVehicle(vehicle: Vehicle): Vehicle {
        vehicles.save(vehicle.toRecord())
        return vehicle
    }

    fun findJobs(): List<DeliveryJob> = jobs.findAll().map { it.toDomain() }

    fun findVehicles(): List<Vehicle> = vehicles.findAll().map { it.toDomain() }

    fun saveProposal(proposal: LastMilePlanProposal) {
        plans.save(proposal.toRecord())
        proposal.carrierVersions.forEach { (jobId, version) ->
            carriers.save(
                LastMilePlanCarrierRecord(
                    planId = proposal.planId.value,
                    planRevision = proposal.planRevision,
                    jobId = jobId.value,
                    carrierVersion = version.value,
                ),
            )
        }
        proposal.routes.forEach { route ->
            route.stops.forEach { stop ->
                stops.save(stop.toRecord(proposal.planId.value, proposal.planRevision, route.vehicleId.value))
            }
        }
        proposal.unassigned.forEach { item ->
            unassigned.save(
                LastMileUnassignedRecord(
                    planId = proposal.planId.value,
                    planRevision = proposal.planRevision,
                    jobId = item.jobId.value,
                    reason = item.reason.name,
                ),
            )
        }
    }

    fun loadProposal(planId: LastMilePlanId, revision: Long): LastMilePlanProposal? {
        val plan = plans.findByPlan(planId.value, revision) ?: return null
        val carrierVersions = carriers.findAllByPlan(planId.value, revision)
            .associate { JobId(it.jobId) to CarrierVersion(it.carrierVersion) }
        val routes = stops.findAllByPlan(planId.value, revision)
            .groupBy { it.vehicleId }
            .toSortedMap()
            .map { (vehicleId, routeStops) ->
                LastMileRoute(
                    vehicleId = io.bluetape4k.workshop.optimization.lastmile.domain.VehicleId(vehicleId),
                    stops = routeStops.sortedBy { it.sequence }.map { it.toDomain() },
                )
            }
        val unassignedJobs = unassigned.findAllByPlan(planId.value, revision)
            .map { LastMileUnassignedJob(JobId(it.jobId), LastMileUnassignedReason.valueOf(it.reason)) }
            .sortedBy { it.jobId.value }
        return LastMilePlanProposal(
            planId = planId,
            planRevision = plan.planRevision,
            parentRevision = plan.parentRevision,
            requestGeneration = plan.requestGeneration,
            matrixRevision = plan.matrixRevision,
            providerRevision = plan.providerRevision?.let(::ProviderRevision),
            carrierVersions = carrierVersions,
            routes = routes,
            unassigned = unassignedJobs,
            score = LastMileScore(
                hardScore = plan.hardScore,
                softScore = plan.softScore,
                assignedJobs = plan.assignedJobs,
                unassignedJobs = plan.unassignedJobs,
            ),
            state = PlanState.valueOf(plan.state),
        )
    }

    fun loadLatestProposal(planId: LastMilePlanId): LastMilePlanProposal? =
        plans.findLatestByPlan(planId.value)?.let { loadProposal(planId, it.planRevision) }

    fun loadLatestProposalForVehicle(vehicleId: io.bluetape4k.workshop.optimization.lastmile.domain.VehicleId): LastMilePlanProposal? =
        stops.findPlanRevisionsByVehicle(vehicleId.value)
            .maxWithOrNull(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first })
            ?.let { (planId, revision) -> loadProposal(LastMilePlanId(planId), revision) }

    fun approveProposal(planId: LastMilePlanId, revision: Long, expectedState: PlanState = PlanState.PROPOSED): Boolean =
        plans.approveIfState(planId.value, revision, expectedState.name)

    fun commitProposal(planId: LastMilePlanId, revision: Long): Boolean =
        plans.commitIfApproved(planId.value, revision)

    fun updateProposalProviderRevision(
        planId: LastMilePlanId,
        revision: Long,
        expectedRevision: ProviderRevision,
        nextRevision: ProviderRevision,
    ): Boolean = plans.updateProviderRevisionIfGreater(
        planId = planId.value,
        planRevision = revision,
        expectedRevision = expectedRevision.value,
        nextRevision = nextRevision.value,
    )

    fun updateProposalProviderRevisionIfAbsent(
        planId: LastMilePlanId,
        revision: Long,
        nextRevision: ProviderRevision,
    ): Boolean = plans.updateProviderRevisionIfAbsent(
        planId = planId.value,
        planRevision = revision,
        nextRevision = nextRevision.value,
    )

    fun appendEvent(event: LastMileEvent): LastMileEventAppendResult {
        val inserted = events.appendIfAbsent(event.toRecord())
        if (inserted) return LastMileEventAppendResult.APPENDED

        val stored = events.findByAggregateAndKey(event.aggregateId, event.eventKey)
            ?: return LastMileEventAppendResult.DIGEST_CONFLICT
        val storedEvent = stored.toDomain()
        return if (LastMileEventCanonicalizer.compare(storedEvent, event) == EventDigestMatch.DUPLICATE) {
            LastMileEventAppendResult.DUPLICATE
        } else {
            LastMileEventAppendResult.DIGEST_CONFLICT
        }
    }

    fun updateJobIfCarrierVersion(jobId: JobId, expected: CarrierVersion, nextStatus: JobStatus): Boolean =
        jobs.updateIfCarrierVersion(jobId.value, expected.value, nextStatus.name)

    private fun DeliveryJob.toRecord() = LastMileJobRecord(
        jobId = jobId.value,
        pickupCoordinateId = pickupCoordinateId.value,
        deliveryCoordinateId = deliveryCoordinateId.value,
        demand = demand,
        pickupWindowStart = pickupWindow.start,
        pickupWindowEnd = pickupWindow.end,
        deliveryWindowStart = deliveryWindow.start,
        deliveryWindowEnd = deliveryWindow.end,
        requiredSkill = requiredSkill,
        priority = priority.name,
        status = status.name,
        carrierVersion = carrierVersion.value,
    )

    private fun Vehicle.toRecord() = LastMileVehicleRecord(
        vehicleId = vehicleId.value,
        driverId = driverId.value,
        depotCoordinateId = depotCoordinateId.value,
        capacity = capacity,
        skills = skills.sorted().joinToString(","),
        availableAt = availableAt,
        startedJobId = startedStop?.jobId?.value,
        startedKind = startedStop?.kind?.name,
        startedCoordinateId = startedStop?.coordinateId?.value,
        startedSequence = startedStop?.sequence,
        startedAt = startedStop?.startedAt,
    )

    private fun LastMilePlanProposal.toRecord() = LastMilePlanRecord(
        planId = planId.value,
        planRevision = planRevision,
        parentRevision = parentRevision,
        requestGeneration = requestGeneration,
        matrixRevision = matrixRevision,
        providerRevision = providerRevision?.value,
        state = state.name,
        hardScore = score.hardScore,
        softScore = score.softScore,
        assignedJobs = score.assignedJobs,
        unassignedJobs = score.unassignedJobs,
    )

    private fun LastMileRouteStop.toRecord(planId: String, planRevision: Long, vehicleId: String) = LastMilePlanStopRecord(
        planId = planId,
        planRevision = planRevision,
        vehicleId = vehicleId,
        jobId = jobId.value,
        kind = kind.name,
        coordinateId = coordinateId.value,
        sequence = sequence,
        eta = eta,
        loadAfter = loadAfter,
        pinned = pinned,
    )

    private fun LastMileJobRecord.toDomain() = DeliveryJob(
        jobId = JobId(jobId),
        pickupCoordinateId = CoordinateId(pickupCoordinateId),
        deliveryCoordinateId = CoordinateId(deliveryCoordinateId),
        demand = demand,
        pickupWindow = TimeWindow(pickupWindowStart, pickupWindowEnd),
        deliveryWindow = TimeWindow(deliveryWindowStart, deliveryWindowEnd),
        requiredSkill = requiredSkill,
        priority = Priority.valueOf(priority),
        carrierVersion = CarrierVersion(carrierVersion),
        status = JobStatus.valueOf(status),
    )

    private fun LastMileVehicleRecord.toDomain() = Vehicle(
        vehicleId = io.bluetape4k.workshop.optimization.lastmile.domain.VehicleId(vehicleId),
        driverId = DriverId(driverId),
        depotCoordinateId = CoordinateId(depotCoordinateId),
        capacity = capacity,
        skills = skills.split(',').filter(String::isNotBlank).toSet(),
        availableAt = availableAt,
        startedStop = if (startedJobId != null && startedKind != null && startedCoordinateId != null &&
            startedSequence != null && startedAt != null
        ) {
            StartedStop(
                jobId = JobId(startedJobId),
                kind = StopKind.valueOf(startedKind),
                coordinateId = CoordinateId(startedCoordinateId),
                sequence = startedSequence,
                startedAt = startedAt,
            )
        } else {
            null
        },
    )

    private fun LastMilePlanStopRecord.toDomain() = LastMileRouteStop(
        jobId = JobId(jobId),
        kind = StopKind.valueOf(kind),
        coordinateId = CoordinateId(coordinateId),
        sequence = sequence,
        eta = eta,
        loadAfter = loadAfter,
        pinned = pinned,
    )

    private fun LastMileEvent.toRecord() = LastMileEventRecord(
        eventId = eventId.value,
        aggregateId = aggregateId,
        eventKey = eventKey,
        eventType = type.name,
        occurredAt = occurredAt,
        canonicalPayload = canonicalPayload,
        digest = digest,
    )

    private fun LastMileEventRecord.toDomain() = LastMileEvent(
        eventId = EventId(eventId),
        type = io.bluetape4k.workshop.optimization.lastmile.domain.LastMileEventType.valueOf(eventType),
        aggregateId = aggregateId,
        eventKey = eventKey,
        occurredAt = occurredAt,
        canonicalPayload = canonicalPayload,
        digest = digest,
    )
}
