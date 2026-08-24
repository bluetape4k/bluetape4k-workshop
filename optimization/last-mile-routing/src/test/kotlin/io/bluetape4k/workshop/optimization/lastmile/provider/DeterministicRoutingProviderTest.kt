package io.bluetape4k.workshop.optimization.lastmile.provider

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.optimization.lastmile.domain.CarrierVersion
import io.bluetape4k.workshop.optimization.lastmile.domain.CoordinateId
import io.bluetape4k.workshop.optimization.lastmile.domain.DeliveryJob
import io.bluetape4k.workshop.optimization.lastmile.domain.DriverId
import io.bluetape4k.workshop.optimization.lastmile.domain.JobId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanId
import io.bluetape4k.workshop.optimization.lastmile.domain.Priority
import io.bluetape4k.workshop.optimization.lastmile.domain.TimeWindow
import io.bluetape4k.workshop.optimization.lastmile.domain.Vehicle
import io.bluetape4k.workshop.optimization.lastmile.planner.CoordinatePair
import io.bluetape4k.workshop.optimization.lastmile.planner.TravelTimeMatrix
import org.junit.jupiter.api.Test
import java.time.Instant

class DeterministicRoutingProviderTest {

    @Test
    fun `same request returns the same provider result and revision`() {
        val input = LastMilePlannerInputFixture.input()
        val provider = DeterministicRoutingProvider()
        val first = provider.submit(RoutingRequest("request-1", input))
        val second = provider.submit(RoutingRequest("request-1", input))

        second shouldBeEqualTo first
        provider.poll(second) shouldBeEqualTo provider.poll(first)
        provider.poll(first)?.providerRevision?.value shouldBeEqualTo 7L
        provider.poll(first)?.proposal.shouldNotBeNull()
    }
}

private object LastMilePlannerInputFixture {
    fun input(): io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlannerInput {
        val depot = CoordinateId("depot")
        val pickup = CoordinateId("pickup")
        val delivery = CoordinateId("delivery")
        return io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlannerInput(
            planId = LastMilePlanId("plan-1"),
            jobs = listOf(
                DeliveryJob(
                    jobId = JobId("job-1"),
                    pickupCoordinateId = pickup,
                    deliveryCoordinateId = delivery,
                    demand = 1,
                    pickupWindow = TimeWindow(Instant.parse("2026-08-24T08:00:00Z"), Instant.parse("2026-08-24T12:00:00Z")),
                    deliveryWindow = TimeWindow(Instant.parse("2026-08-24T08:00:00Z"), Instant.parse("2026-08-24T18:00:00Z")),
                    priority = Priority.NORMAL,
                    carrierVersion = CarrierVersion(1),
                ),
            ),
            vehicles = listOf(
                Vehicle(
                    vehicleId = io.bluetape4k.workshop.optimization.lastmile.domain.VehicleId("vehicle-1"),
                    driverId = DriverId("driver-1"),
                    depotCoordinateId = depot,
                    capacity = 5,
                    availableAt = Instant.parse("2026-08-24T08:00:00Z"),
                ),
            ),
            matrix = TravelTimeMatrix(
                revision = 7,
                coordinateIds = setOf(depot, pickup, delivery),
                edges = mapOf(
                    CoordinatePair(depot, pickup) to 60,
                    CoordinatePair(pickup, delivery) to 60,
                ),
            ),
            planRevision = 1,
            requestGeneration = 1,
        )
    }
}
