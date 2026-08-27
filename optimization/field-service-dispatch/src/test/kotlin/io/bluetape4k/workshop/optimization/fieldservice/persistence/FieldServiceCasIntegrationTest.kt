package io.bluetape4k.workshop.optimization.fieldservice.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.concurrency.TestingExecutors
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceReplanService
import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigest
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventKey
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEventType
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
import io.bluetape4k.workshop.optimization.fieldservice.domain.ProviderRequestId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Skill
import io.bluetape4k.workshop.optimization.fieldservice.domain.VersionVector
import io.bluetape4k.workshop.optimization.fieldservice.domain.Visit
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Worker
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerRoute
import io.bluetape4k.workshop.optimization.fieldservice.planner.DeterministicFieldServicePlanner
import io.bluetape4k.workshop.optimization.fieldservice.planner.PlannerInput
import io.bluetape4k.workshop.optimization.fieldservice.planner.TravelTimeMatrix
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

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
            repository.loadPlan(fixture.plan.planId, fixture.plan.planRevision).shouldNotBeNull().state shouldBeEqualTo PlanState.DRAFT
            repository.findWorker(fixture.worker1.workerId).shouldNotBeNull().version shouldBeEqualTo 0L
            repository.countAssignments() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `approval changes only proposal state`() {
        val fixture = saveFixture()

        approval.approve(fixture.plan.planId, fixture.plan.planRevision, fixture.plan.versionVector) shouldBeEqualTo ApprovalResult.APPROVED
        transaction {
            repository.loadPlan(fixture.plan.planId, fixture.plan.planRevision).shouldNotBeNull().state shouldBeEqualTo PlanState.APPROVED
            repository.findWorker(fixture.worker1.workerId).shouldNotBeNull().version shouldBeEqualTo 0L
            repository.findWorker(fixture.worker1.workerId).shouldNotBeNull().workerScheduleRevision shouldBeEqualTo 0L
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
    fun `concurrent route confirmations commit exactly one worker route`() {
        val fixture = saveFixture()
        approval.approve(fixture.plan.planId, fixture.plan.planRevision, fixture.plan.versionVector)
        val barrier = CyclicBarrier(2)
        val executor = TestingExecutors.newFixedThreadPool(2)

        val results = try {
            executor.invokeAll(
                listOf(1, 2).map {
                    Callable {
                        barrier.await(5, TimeUnit.SECONDS)
                        dispatch.confirmWorkerRoute(fixture.worker1.workerId, fixture.plan.planId, fixture.plan.planRevision)
                    }
                },
                10,
                TimeUnit.SECONDS,
            ).map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        }

        results.map { it.name }.sorted() shouldBeEqualTo listOf("COMMITTED", "SCHEDULE_CONFLICT")
        transaction { repository.countAssignments() shouldBeEqualTo 1L }
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

    @Test
    fun `event append CAS permits exactly one command at the same expected version`() {
        val barrier = CyclicBarrier(2)
        val executor = TestingExecutors.newFixedThreadPool(2)
        val commands = listOf("event-a", "event-b").map { key ->
            FieldServiceCommand(
                aggregateType = "visit",
                aggregateId = AggregateId("visit-cas"),
                eventKey = EventKey(key),
                eventType = FieldServiceEventType.VISIT_URGENT,
                digest = EventDigest("a".repeat(64)),
                payloadSummary = "visit-cas",
                expectedVersion = 0L,
            )
        }

        val results = try {
            executor.invokeAll(
                commands.map { command ->
                    Callable {
                        barrier.await(5, TimeUnit.SECONDS)
                        transaction { repository.appendEvent(command) }
                    }
                },
                10,
                TimeUnit.SECONDS,
            ).map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        }

        results.map { it.name }.sorted() shouldBeEqualTo listOf("APPENDED", "VERSION_CONFLICT")
        transaction {
            repository.countEvents() shouldBeEqualTo 1L
            FieldServiceEventsTable.selectAll().single()[FieldServiceEventsTable.aggregateVersion] shouldBeEqualTo 0L
        }
    }

    @Test
    fun `validated identifiers remain normalized through persisted record round trips`() {
        val worker = worker(" worker-round-trip ").copy(
            skills = setOf(Skill(" electrical ")),
            homeCoordinateId = CoordinateId(" depot-round-trip "),
        )
        val visit = visit(" visit-round-trip ", " coordinate-round-trip ").copy(
            requiredSkill = Skill(" electrical "),
        )
        val plan = PlanProposal(
            planId = PlanId(" plan-round-trip "),
            planRevision = 1,
            parentRevision = null,
            providerRequestId = ProviderRequestId(" provider-round-trip "),
            requestGeneration = 1,
            datasetId = DatasetId(" dataset-round-trip "),
            versionVector = VersionVector(
                visitVersions = mapOf(visit.visitId to 0L),
                workerVersions = mapOf(worker.workerId to 0L),
                workerScheduleRevisions = mapOf(worker.workerId to 0L),
            ),
            routes = listOf(
                WorkerRoute(
                    worker.workerId,
                    listOf(PlannedVisit(visit.visitId, visit.coordinateId, 0, false)),
                ),
            ),
            unassigned = emptyList(),
            score = FieldServiceScoreSummary(0, 0, 1, 0),
        )

        transaction {
            repository.saveWorker(worker)
            repository.saveVisit(visit)
            repository.savePlan(plan)
        }

        val workerPayload = FieldServiceRecordCodec.encodeWorker(worker)
        val visitPayload = FieldServiceRecordCodec.encodeVisit(visit)
        val planPayload = FieldServiceRecordCodec.encode(plan)
        workerPayload.contains("\"workerId\":\"worker-round-trip\"").shouldBeTrue()
        workerPayload.contains("\"workerId\":\" worker-round-trip \"").shouldBeFalse()
        visitPayload.contains("\"visitId\":\"visit-round-trip\"").shouldBeTrue()
        visitPayload.contains("\"visitId\":\" visit-round-trip \"").shouldBeFalse()
        planPayload.contains("\"planId\":\"plan-round-trip\"").shouldBeTrue()
        planPayload.contains("\"planId\":\" plan-round-trip \"").shouldBeFalse()

        transaction {
            repository.findWorker(WorkerId("worker-round-trip")) shouldBeEqualTo worker
            repository.findVisit(VisitId("visit-round-trip")) shouldBeEqualTo visit
            repository.loadPlan(PlanId("plan-round-trip"), 1) shouldBeEqualTo plan
        }
    }

    @Test
    fun `blocking snapshot runs on the virtual thread boundary`() {
        var snapshotWasVirtual = false
        val service = FieldServiceReplanService(
            planner = DeterministicFieldServicePlanner(),
            snapshot = {
                snapshotWasVirtual = Thread.currentThread().isVirtual
                PlannerInput(
                    workers = emptyList(),
                    visits = emptyList(),
                    matrix = TravelTimeMatrix(0L, emptySet(), emptyMap()),
                    datasetId = DatasetId("dataset-empty"),
                    planId = PlanId("plan-empty"),
                )
            },
        )

        try {
            service.await(service.requestReplan(AggregateId("aggregate-empty")))
        } finally {
            service.close()
        }
        snapshotWasVirtual.shouldBeTrue()
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
