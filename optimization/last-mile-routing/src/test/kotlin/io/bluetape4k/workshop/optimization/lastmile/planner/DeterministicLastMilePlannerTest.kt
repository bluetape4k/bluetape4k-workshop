package io.bluetape4k.workshop.optimization.lastmile.planner

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.optimization.lastmile.domain.CarrierVersion
import io.bluetape4k.workshop.optimization.lastmile.domain.CoordinateId
import io.bluetape4k.workshop.optimization.lastmile.domain.DeliveryJob
import io.bluetape4k.workshop.optimization.lastmile.domain.DriverId
import io.bluetape4k.workshop.optimization.lastmile.domain.JobId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlannerInput
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileUnassignedReason
import io.bluetape4k.workshop.optimization.lastmile.domain.Priority
import io.bluetape4k.workshop.optimization.lastmile.domain.StartedStop
import io.bluetape4k.workshop.optimization.lastmile.domain.StopKind
import io.bluetape4k.workshop.optimization.lastmile.domain.TimeWindow
import io.bluetape4k.workshop.optimization.lastmile.domain.Vehicle
import org.junit.jupiter.api.Test
import java.time.Instant

class DeterministicLastMilePlannerTest {

    @Test
    fun `fixed matrix produces the same proposal and revision`() {
        val input = input(jobs = listOf(job("job-1")), vehicles = listOf(vehicle("vehicle-1")))

        val first = DeterministicLastMilePlanner().plan(input)
        val second = DeterministicLastMilePlanner().plan(input)

        second shouldBeEqualTo first
        first.matrixRevision shouldBeEqualTo 7L
    }

    @Test
    fun `pickup always precedes delivery`() {
        val proposal = DeterministicLastMilePlanner().plan(input(jobs = listOf(job("job-1"))))
        val stops = proposal.routes.single().stops

        stops[0].kind shouldBeEqualTo StopKind.PICKUP
        stops[1].kind shouldBeEqualTo StopKind.DELIVERY
        (stops[0].sequence < stops[1].sequence).shouldBeTrue()
    }

    @Test
    fun `capacity violation becomes unassigned`() {
        val proposal = DeterministicLastMilePlanner().plan(
            input(jobs = listOf(job("job-1", demand = 4)), vehicles = listOf(vehicle("vehicle-1", capacity = 3))),
        )

        proposal.unassigned.single().reason shouldBeEqualTo LastMileUnassignedReason.CAPACITY
    }

    @Test
    fun `missing skill becomes unassigned`() {
        val proposal = DeterministicLastMilePlanner().plan(
            input(jobs = listOf(job("job-1", requiredSkill = "COLD")), vehicles = listOf(vehicle("vehicle-1"))),
        )

        proposal.unassigned.single().reason shouldBeEqualTo LastMileUnassignedReason.MISSING_SKILL
    }

    @Test
    fun `time window violation becomes unassigned`() {
        val proposal = DeterministicLastMilePlanner().plan(
            input(jobs = listOf(job("job-1", pickupWindow = TimeWindow(at("2026-08-24T08:00:00Z"), at("2026-08-24T08:00:30Z")))), vehicles = listOf(vehicle("vehicle-1"))),
        )

        proposal.unassigned.single().reason shouldBeEqualTo LastMileUnassignedReason.TIME_WINDOW
    }

    @Test
    fun `matrix miss never silently falls back`() {
        val matrix = TravelTimeMatrix(
            revision = 7,
            coordinateIds = setOf(CoordinateId("depot"), CoordinateId("pickup"), CoordinateId("delivery")),
            edges = mapOf(
                CoordinatePair(CoordinateId("pickup"), CoordinateId("delivery")) to 30,
            ),
        )

        val proposal = DeterministicLastMilePlanner().plan(input(jobs = listOf(job("job-1")), matrix = matrix))

        proposal.unassigned.single().reason shouldBeEqualTo LastMileUnassignedReason.MATRIX_MISS
    }

    @Test
    fun `started stop remains pinned`() {
        val started = StartedStop(
            jobId = JobId("job-started"),
            kind = StopKind.PICKUP,
            coordinateId = CoordinateId("pickup"),
            sequence = 0,
            startedAt = at("2026-08-24T08:01:00Z"),
        )
        val proposal = DeterministicLastMilePlanner().plan(
            input(
                jobs = listOf(job("job-started")),
                vehicles = listOf(vehicle("vehicle-1", startedStop = started)),
            ),
        )

        val pinned = proposal.routes.single().stops.firstOrNull { it.jobId == JobId("job-started") }
        pinned.shouldNotBeNull()
        pinned.pinned.shouldBeTrue()
        pinned.sequence shouldBeEqualTo 0
    }

    private fun input(
        jobs: List<DeliveryJob> = listOf(job("job-1")),
        vehicles: List<Vehicle> = listOf(vehicle("vehicle-1")),
        matrix: TravelTimeMatrix = matrix(),
    ): LastMilePlannerInput = LastMilePlannerInput(
        planId = LastMilePlanId("plan-1"),
        jobs = jobs,
        vehicles = vehicles,
        matrix = matrix,
        planRevision = 1,
        requestGeneration = 1,
    )

    private fun matrix(): TravelTimeMatrix = TravelTimeMatrix(
        revision = 7,
        coordinateIds = setOf(CoordinateId("depot"), CoordinateId("pickup"), CoordinateId("delivery")),
        edges = mapOf(
            CoordinatePair(CoordinateId("depot"), CoordinateId("pickup")) to 60,
            CoordinatePair(CoordinateId("pickup"), CoordinateId("delivery")) to 60,
        ),
    )

    private fun job(
        id: String,
        demand: Int = 1,
        requiredSkill: String? = null,
        pickupWindow: TimeWindow = TimeWindow(at("2026-08-24T08:00:00Z"), at("2026-08-24T12:00:00Z")),
    ): DeliveryJob = DeliveryJob(
        jobId = JobId(id),
        pickupCoordinateId = CoordinateId("pickup"),
        deliveryCoordinateId = CoordinateId("delivery"),
        demand = demand,
        pickupWindow = pickupWindow,
        deliveryWindow = TimeWindow(at("2026-08-24T08:00:00Z"), at("2026-08-24T18:00:00Z")),
        requiredSkill = requiredSkill,
        priority = Priority.NORMAL,
        carrierVersion = CarrierVersion(1),
    )

    private fun vehicle(
        id: String,
        capacity: Int = 10,
        startedStop: StartedStop? = null,
    ): Vehicle = Vehicle(
        vehicleId = io.bluetape4k.workshop.optimization.lastmile.domain.VehicleId(id),
        driverId = DriverId("driver-$id"),
        depotCoordinateId = CoordinateId("depot"),
        capacity = capacity,
        skills = setOf("GENERAL"),
        availableAt = at("2026-08-24T08:00:00Z"),
        startedStop = startedStop,
    )

    private fun at(value: String): Instant = Instant.parse(value)
}
