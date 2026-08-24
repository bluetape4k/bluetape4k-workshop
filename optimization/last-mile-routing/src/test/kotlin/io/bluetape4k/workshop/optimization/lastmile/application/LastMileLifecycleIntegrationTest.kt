package io.bluetape4k.workshop.optimization.lastmile.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.lastmile.domain.CarrierVersion
import io.bluetape4k.workshop.optimization.lastmile.domain.CoordinateId
import io.bluetape4k.workshop.optimization.lastmile.domain.DeliveryJob
import io.bluetape4k.workshop.optimization.lastmile.domain.DriverId
import io.bluetape4k.workshop.optimization.lastmile.domain.EventId
import io.bluetape4k.workshop.optimization.lastmile.domain.JobId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileEventType
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlanProposal
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileRoute
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileRouteStop
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileScore
import io.bluetape4k.workshop.optimization.lastmile.domain.PlanState
import io.bluetape4k.workshop.optimization.lastmile.domain.Priority
import io.bluetape4k.workshop.optimization.lastmile.domain.ProviderRevision
import io.bluetape4k.workshop.optimization.lastmile.domain.StopKind
import io.bluetape4k.workshop.optimization.lastmile.domain.TimeWindow
import io.bluetape4k.workshop.optimization.lastmile.domain.Vehicle
import io.bluetape4k.workshop.optimization.lastmile.domain.VehicleId
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileAuditRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileCallbackInboxRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileCommittedStopsTable
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileEventRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileJobRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMilePlanCarrierRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMilePlanRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMilePlanStopRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileTables
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileUnassignedRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileVehicleRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileCommittedStopRepository
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileOutboxRepository
import io.bluetape4k.workshop.optimization.lastmile.planner.CoordinatePair
import io.bluetape4k.workshop.optimization.lastmile.planner.TravelTimeMatrix
import io.bluetape4k.workshop.optimization.lastmile.provider.DeterministicRoutingProvider
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingCallback
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingCallbackCanonicalizer
import io.bluetape4k.workshop.optimization.lastmile.provider.RoutingRequest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LastMileLifecycleIntegrationTest {
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
    private val fixedClock = Clock.fixed(NOW, java.time.ZoneOffset.UTC)

    @BeforeAll
    fun connect() {
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
    fun `callback inbox is idempotent and stale provider revisions are audit only`() {
        val provider = DeterministicRoutingProvider()
        val submission = provider.submit(RoutingRequest("request-1", input()))
        val result = provider.poll(submission).shouldNotBeNull()
        val callbackService = LastMileCallbackService(
            provider = provider,
            inboxRepository = LastMileCallbackInboxRepository(),
            repository = repository,
            auditRepository = LastMileAuditRepository(),
            clock = fixedClock,
        )
        val draft = RoutingCallback(
            provider = result.provider,
            eventId = EventId("event-1"),
            requestId = result.requestId,
            providerRevision = result.providerRevision,
            payloadDigest = "0".repeat(64),
            result = result,
        )
        val callback = draft.copy(payloadDigest = RoutingCallbackCanonicalizer.digest(draft))

        transaction {
            callbackService.handle(callback) shouldBeEqualTo io.bluetape4k.workshop.optimization.lastmile.provider.CallbackDecision.ACCEPTED
            callbackService.handle(callback) shouldBeEqualTo io.bluetape4k.workshop.optimization.lastmile.provider.CallbackDecision.DUPLICATE
            callbackService.handle(
                callback.copy(
                    eventId = EventId("event-2"),
                    providerRevision = ProviderRevision(1),
                    result = callback.result.copy(providerRevision = ProviderRevision(1)),
                    payloadDigest = RoutingCallbackCanonicalizer.digest(
                        callback.copy(
                            eventId = EventId("event-2"),
                            providerRevision = ProviderRevision(1),
                            result = callback.result.copy(providerRevision = ProviderRevision(1)),
                        ),
                    ),
                ),
            ) shouldBeEqualTo io.bluetape4k.workshop.optimization.lastmile.provider.CallbackDecision.STALE_PROVIDER_REVISION
        }
    }

    @Test
    fun `traffic burst shares generation while new event key advances it`() {
        val eventService = LastMileEventService(repository, fixedClock)
        transaction {
            val first = eventService.append(
                LastMileEventCommand(
                    type = LastMileEventType.TRAFFIC_DURATION_UPDATED,
                    aggregateId = "job-1",
                    eventKey = "traffic-1",
                    payload = mapOf("seconds" to "60"),
                ),
            )
            val duplicate = eventService.append(
                LastMileEventCommand(
                    type = LastMileEventType.TRAFFIC_DURATION_UPDATED,
                    aggregateId = "job-1",
                    eventKey = "traffic-1",
                    payload = mapOf("seconds" to "60"),
                ),
            )
            val next = eventService.append(
                LastMileEventCommand(
                    type = LastMileEventType.PICKUP_WINDOW_CHANGED,
                    aggregateId = "job-1",
                    eventKey = "pickup-1",
                    payload = mapOf("window" to "morning"),
                ),
            )
            first.requestGeneration shouldBeEqualTo 1L
            duplicate.appendResult shouldBeEqualTo io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileEventAppendResult.DUPLICATE
            duplicate.requestGeneration shouldBeEqualTo 1L
            next.requestGeneration shouldBeEqualTo 2L
        }
    }

    @Test
    fun `approval commits both route stops and stale retry performs no write`() {
        val committedStops = LastMileCommittedStopRepository()
        val outbox = LastMileOutboxRepository()
        val service = LastMileApprovalService(
            repository = repository,
            committedStopRepository = committedStops,
            outboxRepository = outbox,
            auditRepository = LastMileAuditRepository(),
            clock = fixedClock,
        )
        transaction {
            repository.saveJob(job())
            repository.saveVehicle(vehicle())
            repository.saveProposal(proposal())
            val command = LastMileApprovalCommand(
                planId = LastMilePlanId("plan-1"),
                planRevision = 1L,
                expectedMatrixRevision = 3L,
                expectedCarrierVersions = mapOf(JobId("job-1") to CarrierVersion(0)),
            )
            service.approve(command) shouldBeEqualTo LastMileApprovalResult.COMMITTED
            LastMileCommittedStopsTable.selectAll().toList().size shouldBeEqualTo 2
            repository.loadProposal(LastMilePlanId("plan-1"), 1).shouldNotBeNull().state shouldBeEqualTo PlanState.COMMITTED
            service.approve(command) shouldBeEqualTo LastMileApprovalResult.STALE_ROUTE_APPROVAL
            LastMileCommittedStopsTable.selectAll().toList().size shouldBeEqualTo 2
        }
    }

    private fun input() = io.bluetape4k.workshop.optimization.lastmile.domain.LastMilePlannerInput(
        planId = LastMilePlanId("plan-1"),
        jobs = listOf(job()),
        vehicles = listOf(vehicle()),
        matrix = TravelTimeMatrix(
            revision = 7,
            coordinateIds = setOf(CoordinateId("depot"), CoordinateId("pickup"), CoordinateId("delivery")),
            edges = mapOf(
                CoordinatePair(CoordinateId("depot"), CoordinateId("pickup")) to 60,
                CoordinatePair(CoordinateId("pickup"), CoordinateId("delivery")) to 60,
            ),
        ),
        planRevision = 1,
        requestGeneration = 1,
    )

    private fun job() = DeliveryJob(
        jobId = JobId("job-1"),
        pickupCoordinateId = CoordinateId("pickup"),
        deliveryCoordinateId = CoordinateId("delivery"),
        demand = 1,
        pickupWindow = TimeWindow(NOW, NOW.plusSeconds(900)),
        deliveryWindow = TimeWindow(NOW, NOW.plusSeconds(1_800)),
        priority = Priority.NORMAL,
    )

    private fun vehicle() = Vehicle(
        vehicleId = VehicleId("vehicle-1"),
        driverId = DriverId("driver-1"),
        depotCoordinateId = CoordinateId("depot"),
        capacity = 5,
        availableAt = NOW,
    )

    private fun proposal() = LastMilePlanProposal(
        planId = LastMilePlanId("plan-1"),
        planRevision = 1,
        parentRevision = null,
        requestGeneration = 1,
        matrixRevision = 3,
        carrierVersions = mapOf(JobId("job-1") to CarrierVersion(0)),
        routes = listOf(
            LastMileRoute(
                vehicleId = VehicleId("vehicle-1"),
                stops = listOf(
                    LastMileRouteStop(JobId("job-1"), StopKind.PICKUP, CoordinateId("pickup"), 0, NOW, 1, false),
                    LastMileRouteStop(JobId("job-1"), StopKind.DELIVERY, CoordinateId("delivery"), 1, NOW.plusSeconds(60), 0, false),
                ),
            ),
        ),
        unassigned = emptyList(),
        score = LastMileScore(1000, -2, 1, 0),
    )

    companion object {
        private val NOW: Instant = Instant.parse("2026-08-24T00:00:00Z")
    }
}
