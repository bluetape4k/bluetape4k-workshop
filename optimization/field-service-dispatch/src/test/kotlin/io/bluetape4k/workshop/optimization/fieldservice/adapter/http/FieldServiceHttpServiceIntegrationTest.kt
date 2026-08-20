package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceApprovalService
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceCommandService
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceDispatchService
import io.bluetape4k.workshop.optimization.fieldservice.domain.AvailabilityWindow
import io.bluetape4k.workshop.optimization.fieldservice.domain.ConstraintReasonCode
import io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Skill
import io.bluetape4k.workshop.optimization.fieldservice.domain.Visit
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Worker
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceTables
import io.bluetape4k.workshop.optimization.fieldservice.planner.DeterministicFieldServicePlanner
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldServiceHttpServiceIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = FieldServiceRepository()
    private lateinit var service: FieldServiceHttpService
    private val start = Instant.parse("2026-08-20T09:00:00Z")

    @BeforeAll
    fun connect() {
        Database.connect(postgres.jdbcUrl, "org.postgresql.Driver", requireNotNull(postgres.username), requireNotNull(postgres.password))
        service = FieldServiceHttpService(
            repository = repository,
            commandService = FieldServiceCommandService(repository),
            planner = DeterministicFieldServicePlanner(),
            approvalService = FieldServiceApprovalService(repository),
            dispatchService = FieldServiceDispatchService(repository),
        )
    }

    @BeforeEach
    fun schema() {
        transaction {
            SchemaUtils.drop(*FieldServiceTables.all.reversedArray())
            SchemaUtils.create(*FieldServiceTables.all)
        }
    }

    @Test
    fun `create visit retry with the same key returns durable duplicate`() {
        val request = createRequest()

        service.createVisit(request, "create-key") shouldBeEqualTo io.bluetape4k.workshop.optimization.fieldservice.application.CommandResult.APPLIED
        service.createVisit(request, "create-key") shouldBeEqualTo io.bluetape4k.workshop.optimization.fieldservice.application.CommandResult.DUPLICATE
        transaction {
            repository.findVisit(VisitId("visit-1"))?.visitId shouldBeEqualTo VisitId("visit-1")
            repository.countEvents() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `replan retry with the same key returns the original revision`() {
        val request = ReplanRequest("plan-1", "dataset-1")

        val first = service.replan(request, "replan-key")
        val duplicate = service.replan(request, "replan-key")

        duplicate shouldBeEqualTo first
        transaction { repository.listPlans(PlanId("plan-1")).size shouldBeEqualTo 1 }
    }

    @Test
    fun `travel time projection changes the next replan assignment`() {
        transaction {
            repository.saveWorker(
                Worker(
                    workerId = WorkerId("worker-1"),
                    name = "Synthetic worker",
                    skills = setOf(Skill("electrical")),
                    availability = listOf(AvailabilityWindow(start, start.plus(Duration.ofHours(9)))),
                    homeCoordinateId = CoordinateId("depot"),
                ),
            )
            repository.saveVisit(
                Visit(
                    visitId = VisitId("visit-1"),
                    coordinateId = CoordinateId("visit"),
                    requiredSkill = Skill("electrical"),
                    windowStart = start,
                    windowEnd = start.plus(Duration.ofHours(1)),
                    serviceDuration = Duration.ofMinutes(15),
                ),
            )
        }
        service.recordTravelTime(TravelTimeUpdateRequest("depot", "visit", 60), "travel-key-1")
        val assigned = service.replan(ReplanRequest("plan-1", "dataset-1"), "replan-key-1")
        service.recordTravelTime(TravelTimeUpdateRequest("depot", "visit", 7_200), "travel-key-2")
        val unassigned = service.replan(ReplanRequest("plan-1", "dataset-1"), "replan-key-2")

        assigned.score.assignedCount shouldBeEqualTo 1
        unassigned.score.assignedCount shouldBeEqualTo 0
        unassigned.unassigned.single().reason shouldBeEqualTo ConstraintReasonCode.TIME_WINDOW
    }

    private fun createRequest() = CreateVisitRequest(
        visitId = "visit-1",
        coordinateId = "coordinate-1",
        requiredSkill = "electrical",
        windowStart = start,
        windowEnd = start.plus(Duration.ofHours(1)),
        serviceDurationSeconds = 60,
    )
}
