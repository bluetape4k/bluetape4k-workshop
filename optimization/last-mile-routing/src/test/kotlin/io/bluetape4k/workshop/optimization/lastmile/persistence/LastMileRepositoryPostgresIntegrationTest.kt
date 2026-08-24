package io.bluetape4k.workshop.optimization.lastmile.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.lastmile.domain.CarrierVersion
import io.bluetape4k.workshop.optimization.lastmile.domain.CoordinateId
import io.bluetape4k.workshop.optimization.lastmile.domain.DeliveryJob
import io.bluetape4k.workshop.optimization.lastmile.domain.EventId
import io.bluetape4k.workshop.optimization.lastmile.domain.JobStatus
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileEvent
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileEventCanonicalizer
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileEventType
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanProposal
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileRoute
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileRouteStop
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileScore
import io.bluetape4k.workshop.optimization.lastmile.domain.PlanState
import io.bluetape4k.workshop.optimization.lastmile.domain.StopKind
import io.bluetape4k.workshop.optimization.lastmile.domain.TimeWindow
import io.bluetape4k.workshop.optimization.lastmile.domain.Vehicle
import io.bluetape4k.workshop.optimization.lastmile.domain.VehicleId
import io.bluetape4k.workshop.optimization.lastmile.domain.DriverId
import io.bluetape4k.workshop.optimization.lastmile.domain.Priority
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LastMileRepositoryPostgresIntegrationTest {

    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = LastMileRepository(
        jobs = LastMileJobRepository(),
        vehicles = LastMileVehicleRepository(),
        plans = LastMilePlanRepository(),
        carriers = LastMilePlanCarrierRepository(),
        stops = LastMilePlanStopRepository(),
        unassigned = LastMileUnassignedRepository(),
        events = LastMileEventRepository(),
    )

    @BeforeAll
    fun connectPostgres() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username.shouldNotBeNull(),
            password = postgres.password.shouldNotBeNull(),
        )
    }

    @BeforeEach
    fun createSchema() {
        transaction {
            SchemaUtils.drop(*LastMileTables.all.reversedArray())
            SchemaUtils.create(*LastMileTables.all)
        }
    }

    @AfterEach
    fun dropSchema() {
        transaction { SchemaUtils.drop(*LastMileTables.all.reversedArray()) }
    }

    @Test
    fun `job carrier version is compare and set through bluetape repository`() {
        transaction {
            repository.saveJob(job())

            repository.updateJobIfCarrierVersion(job().jobId, CarrierVersion(0), JobStatus.COMPLETED) shouldBeEqualTo true
            repository.updateJobIfCarrierVersion(job().jobId, CarrierVersion(0), JobStatus.CANCELLED) shouldBeEqualTo false
            repository.findJobs().single().status shouldBeEqualTo JobStatus.COMPLETED
        }
    }

    @Test
    fun `proposal round trip keeps routes and approval state`() {
        transaction {
            repository.saveProposal(proposal())

            val loaded = repository.loadProposal(LastMilePlanId("plan-1"), 1).shouldNotBeNull()
            loaded.routes.single().stops.map { it.kind } shouldBeEqualTo listOf(StopKind.PICKUP, StopKind.DELIVERY)
            repository.approveProposal(LastMilePlanId("plan-1"), 1) shouldBeEqualTo true
            repository.approveProposal(LastMilePlanId("plan-1"), 1) shouldBeEqualTo false
            repository.loadProposal(LastMilePlanId("plan-1"), 1).shouldNotBeNull().state shouldBeEqualTo PlanState.APPROVED
        }
    }

    @Test
    fun `event append is idempotent and rejects digest conflict`() {
        transaction {
            val event = event("payload")
            repository.appendEvent(event) shouldBeEqualTo LastMileEventAppendResult.APPENDED
            repository.appendEvent(event) shouldBeEqualTo LastMileEventAppendResult.DUPLICATE
            repository.appendEvent(event("changed")) shouldBeEqualTo LastMileEventAppendResult.DIGEST_CONFLICT
        }
    }

    private fun job() = DeliveryJob(
        jobId = io.bluetape4k.workshop.optimization.lastmile.domain.JobId("job-1"),
        pickupCoordinateId = CoordinateId("depot"),
        deliveryCoordinateId = CoordinateId("customer"),
        demand = 1,
        pickupWindow = TimeWindow(NOW, NOW.plusSeconds(900)),
        deliveryWindow = TimeWindow(NOW, NOW.plusSeconds(1_800)),
        priority = Priority.NORMAL,
    )

    private fun proposal() = LastMilePlanProposal(
        planId = LastMilePlanId("plan-1"),
        planRevision = 1,
        parentRevision = null,
        requestGeneration = 1,
        matrixRevision = 3,
        carrierVersions = mapOf(io.bluetape4k.workshop.optimization.lastmile.domain.JobId("job-1") to CarrierVersion(0)),
        routes = listOf(
            LastMileRoute(
                vehicleId = VehicleId("vehicle-1"),
                stops = listOf(
                    LastMileRouteStop(io.bluetape4k.workshop.optimization.lastmile.domain.JobId("job-1"), StopKind.PICKUP, CoordinateId("depot"), 0, NOW, 1, true),
                    LastMileRouteStop(io.bluetape4k.workshop.optimization.lastmile.domain.JobId("job-1"), StopKind.DELIVERY, CoordinateId("customer"), 1, NOW.plusSeconds(60), 0, false),
                ),
            ),
        ),
        unassigned = emptyList(),
        score = LastMileScore(0, -60, 1, 0),
    )

    private fun event(payload: String) = LastMileEvent(
        eventId = EventId("event-${payload.hashCode().toString().replace('-', 'n')}"),
        type = LastMileEventType.TRAFFIC_DURATION_UPDATED,
        aggregateId = "job-1",
        eventKey = "traffic-1",
        occurredAt = NOW,
        canonicalPayload = LastMileEventCanonicalizer.canonicalize(
            LastMileEventType.TRAFFIC_DURATION_UPDATED,
            "job-1",
            "traffic-1",
            mapOf("duration" to payload),
        ),
    )

    companion object {
        private val NOW: Instant = Instant.parse("2026-08-24T00:00:00Z")
    }
}
