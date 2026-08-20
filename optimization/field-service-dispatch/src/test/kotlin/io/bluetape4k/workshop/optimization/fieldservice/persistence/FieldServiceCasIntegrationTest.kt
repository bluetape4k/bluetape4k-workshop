package io.bluetape4k.workshop.optimization.fieldservice.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.fieldservice.application.ApprovalResult
import io.bluetape4k.workshop.optimization.fieldservice.application.DispatchResult
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceApprovalService
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceDispatchService
import io.bluetape4k.workshop.optimization.fieldservice.domain.AvailabilityWindow
import io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceScoreSummary
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanProposal
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanState
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlannedVisit
import io.bluetape4k.workshop.optimization.fieldservice.domain.Skill
import io.bluetape4k.workshop.optimization.fieldservice.domain.VersionVector
import io.bluetape4k.workshop.optimization.fieldservice.domain.Visit
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Worker
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerRoute
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
class FieldServiceCasIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = FieldServiceRepository()
    private val approval = FieldServiceApprovalService(repository)
    private val dispatch = FieldServiceDispatchService(repository)
    private val start = Instant.parse("2026-08-20T09:00:00Z")
    private val end = Instant.parse("2026-08-20T18:00:00Z")

    @BeforeAll
    fun connect() {
        Database.connect(postgres.jdbcUrl, "org.postgresql.Driver", requireNotNull(postgres.username), requireNotNull(postgres.password))
    }

    @BeforeEach
    fun schema() {
        transaction {
            SchemaUtils.drop(*FieldServiceTables.all.reversedArray())
            SchemaUtils.create(*FieldServiceTables.all)
        }
    }

    @Test
    fun `approval version conflict changes neither proposal nor business versions`() {
        val fixture = saveFixture()
        transaction {
            repository.updateVisitIfVersion(fixture.visit1.visitId, 0L, fixture.visit1.copy(version = 1L)).shouldBeTrue()
        }

        approval.approve(fixture.plan.planId, fixture.plan.planRevision, fixture.plan.versionVector) shouldBeEqualTo ApprovalResult.VERSION_CONFLICT
        transaction {
            repository.loadPlan(fixture.plan.planId, fixture.plan.planRevision)!!.state shouldBeEqualTo PlanState.DRAFT
            repository.findWorker(fixture.worker1.workerId)!!.version shouldBeEqualTo 0L
            repository.countAssignments() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `approval changes only proposal state`() {
        val fixture = saveFixture()

        approval.approve(fixture.plan.planId, fixture.plan.planRevision, fixture.plan.versionVector) shouldBeEqualTo ApprovalResult.APPROVED
        transaction {
            repository.loadPlan(fixture.plan.planId, fixture.plan.planRevision)!!.state shouldBeEqualTo PlanState.APPROVED
            repository.findWorker(fixture.worker1.workerId)!!.version shouldBeEqualTo 0L
            repository.findWorker(fixture.worker1.workerId)!!.workerScheduleRevision shouldBeEqualTo 0L
        }
    }

    @Test
    fun `same worker route conflict rolls back while another worker remains confirmable`() {
        val fixture = saveFixture()
        approval.approve(fixture.plan.planId, fixture.plan.planRevision, fixture.plan.versionVector)

        dispatch.confirmWorkerRoute(fixture.worker1.workerId, fixture.plan.planId, fixture.plan.planRevision) shouldBeEqualTo DispatchResult.COMMITTED
        dispatch.confirmWorkerRoute(fixture.worker1.workerId, fixture.plan.planId, fixture.plan.planRevision) shouldBeEqualTo DispatchResult.SCHEDULE_CONFLICT
        dispatch.confirmWorkerRoute(fixture.worker2.workerId, fixture.plan.planId, fixture.plan.planRevision) shouldBeEqualTo DispatchResult.COMMITTED
        transaction { repository.countAssignments() shouldBeEqualTo 2L }
    }

    @Test
    fun `route confirmation rejects a visit changed after proposal creation`() {
        val fixture = saveFixture()
        approval.approve(fixture.plan.planId, fixture.plan.planRevision, fixture.plan.versionVector)
        transaction {
            repository.updateVisitIfVersion(
                fixture.visit1.visitId,
                expectedVersion = 0L,
                next = fixture.visit1.copy(version = 1L),
            ).shouldBeTrue()
        }

        dispatch.confirmWorkerRoute(fixture.worker1.workerId, fixture.plan.planId, fixture.plan.planRevision) shouldBeEqualTo
            DispatchResult.VERSION_CONFLICT
        transaction { repository.countAssignments() shouldBeEqualTo 0L }
    }

    private fun saveFixture(): Fixture = transaction {
        val worker1 = worker("worker-1")
        val worker2 = worker("worker-2")
        val visit1 = visit("visit-1", "coordinate-1")
        val visit2 = visit("visit-2", "coordinate-2")
        repository.saveWorker(worker1)
        repository.saveWorker(worker2)
        repository.saveVisit(visit1)
        repository.saveVisit(visit2)
        val plan = PlanProposal(
            planId = PlanId("plan-1"),
            planRevision = 1,
            parentRevision = null,
            requestGeneration = 1,
            datasetId = DatasetId("dataset-1"),
            versionVector = VersionVector(
                visitVersions = mapOf(visit1.visitId to 0L, visit2.visitId to 0L),
                workerVersions = mapOf(worker1.workerId to 0L, worker2.workerId to 0L),
                workerScheduleRevisions = mapOf(worker1.workerId to 0L, worker2.workerId to 0L),
            ),
            routes = listOf(
                WorkerRoute(worker1.workerId, listOf(PlannedVisit(visit1.visitId, visit1.coordinateId, 0, false))),
                WorkerRoute(worker2.workerId, listOf(PlannedVisit(visit2.visitId, visit2.coordinateId, 0, false))),
            ),
            unassigned = emptyList(),
            score = FieldServiceScoreSummary(2_000, 0, 2, 0),
            state = PlanState.DRAFT,
        )
        repository.savePlan(plan)
        Fixture(worker1, worker2, visit1, visit2, plan)
    }

    private fun worker(id: String) = Worker(
        WorkerId(id), "Synthetic $id", setOf(Skill("electrical")), listOf(AvailabilityWindow(start, end)), CoordinateId("depot-$id"),
    )

    private fun visit(id: String, coordinate: String) = Visit(
        VisitId(id), CoordinateId(coordinate), Skill("electrical"), start, end, Duration.ofMinutes(15),
    )

    private data class Fixture(val worker1: Worker, val worker2: Worker, val visit1: Visit, val visit2: Visit, val plan: PlanProposal)
}
