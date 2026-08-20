package io.bluetape4k.workshop.optimization.fieldservice.planner

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.fieldservice.domain.AvailabilityWindow
import io.bluetape4k.workshop.optimization.fieldservice.domain.ConstraintReasonCode
import io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Skill
import io.bluetape4k.workshop.optimization.fieldservice.domain.Visit
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitPin
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitPriority
import io.bluetape4k.workshop.optimization.fieldservice.domain.Worker
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test

class DeterministicFieldServicePlannerTest {
    private val dayStart = Instant.parse("2026-08-20T09:00:00Z")
    private val dayEnd = Instant.parse("2026-08-20T18:00:00Z")
    private val skill = Skill("electrical")
    private val matrix = TravelTimeMatrix(
        revision = 1,
        coordinateIds = setOf(CoordinateId("depot"), CoordinateId("a"), CoordinateId("b"), CoordinateId("c")),
        edges = buildMap {
            val ids = listOf(CoordinateId("depot"), CoordinateId("a"), CoordinateId("b"), CoordinateId("c"))
            ids.forEach { from -> ids.forEach { to -> put(CoordinatePair(from, to), if (from == to) 0L else 10L) } }
        },
    )
    private val planner = DeterministicFieldServicePlanner()

    @Test
    fun `urgent visits sort before normal visits then window and id`() {
        val normal = visit("visit-1", CoordinateId("a"), priority = VisitPriority.NORMAL)
        val urgent = visit("visit-2", CoordinateId("b"), priority = VisitPriority.URGENT)
        val result = planner.plan(input(visits = listOf(normal, urgent)))

        result.routes.single().visits.map { it.visitId.value } shouldBeEqualTo listOf("visit-2", "visit-1")
    }

    @Test
    fun `missing skill becomes MISSING_SKILL without assignment`() {
        val result = planner.plan(input(visits = listOf(visit("visit-1", CoordinateId("a"), Skill("plumbing")))))

        result.unassigned.single().reason shouldBeEqualTo ConstraintReasonCode.MISSING_SKILL
        result.routes.single().visits.size shouldBeEqualTo 0
    }

    @Test
    fun `unavailable worker is excluded`() {
        val result = planner.plan(input(workers = listOf(worker(unavailable = true))))

        result.unassigned.single().reason shouldBeEqualTo ConstraintReasonCode.UNAVAILABLE
    }

    @Test
    fun `travel time and service duration must fit the time window`() {
        val narrow = visit(
            "visit-1",
            CoordinateId("a"),
            windowStart = dayStart,
            windowEnd = dayStart.plusSeconds(5),
            serviceDuration = Duration.ofSeconds(10),
        )
        val result = planner.plan(input(visits = listOf(narrow)))

        result.unassigned.single().reason shouldBeEqualTo ConstraintReasonCode.TIME_WINDOW
    }

    @Test
    fun `planner waits for a later disjoint worker availability window`() {
        val splitWorker = worker().copy(
            availability = listOf(
                AvailabilityWindow(dayStart, dayStart.plus(Duration.ofHours(1))),
                AvailabilityWindow(dayStart.plus(Duration.ofHours(5)), dayStart.plus(Duration.ofHours(9))),
            ),
        )
        val longTravel = TravelTimeMatrix(
            revision = 2,
            coordinateIds = setOf(CoordinateId("depot"), CoordinateId("a")),
            edges = mapOf(CoordinatePair(CoordinateId("depot"), CoordinateId("a")) to 7_200L),
        )

        val result = planner.plan(input(workers = listOf(splitWorker), matrix = longTravel))

        result.routes.single().visits.map { it.visitId } shouldBeEqualTo listOf(VisitId("visit-1"))
        result.unassigned shouldBeEqualTo emptyList()
    }

    @Test
    fun `started or manually pinned visit keeps worker and route order`() {
        val pinned = visit("visit-pinned", CoordinateId("b"), pin = VisitPin(WorkerId("worker-2"), 0), started = true)
        val movable = visit("visit-movable", CoordinateId("a"))
        val result = planner.plan(input(workers = listOf(worker("worker-1"), worker("worker-2")), visits = listOf(movable, pinned)))

        result.routes.first { it.workerId.value == "worker-2" }.visits.first().visitId shouldBeEqualTo VisitId("visit-pinned")
        result.routes.first { it.workerId.value == "worker-1" }.visits.single().visitId shouldBeEqualTo VisitId("visit-movable")
        result.unassigned.size shouldBeEqualTo 0
    }

    @Test
    fun `pinned route order is preserved when movable visits follow it`() {
        val pinned = visit("visit-pinned", CoordinateId("b"), pin = VisitPin(WorkerId("worker-1"), 5))
        val movable = visit("visit-movable", CoordinateId("a"))
        val route = planner.plan(input(visits = listOf(pinned, movable))).routes.single()

        route.visits.map { it.visitId.value to it.routeOrder } shouldBeEqualTo listOf(
            "visit-pinned" to 5,
            "visit-movable" to 6,
        )
    }

    @Test
    fun `same input produces identical routes scores and reasons`() {
        val visits = listOf(visit("visit-1", CoordinateId("a")), visit("visit-2", CoordinateId("b"), priority = VisitPriority.URGENT))
        val first = planner.plan(input(visits = visits))
        val second = planner.plan(input(visits = visits))

        first shouldBeEqualTo second
    }

    @Test
    fun `missing matrix edge becomes TRAVEL_TIME without external call`() {
        val missing = TravelTimeMatrix(
            revision = 2,
            coordinateIds = setOf(CoordinateId("depot"), CoordinateId("a")),
            edges = mapOf(CoordinatePair(CoordinateId("depot"), CoordinateId("depot")) to 0L),
        )
        val result = planner.planWithMetrics(input(matrix = missing, visits = listOf(visit("visit-1", CoordinateId("a")))))

        result.proposal.unassigned.single().reason shouldBeEqualTo ConstraintReasonCode.TRAVEL_TIME
        result.metrics.externalCalls shouldBeEqualTo 0
        result.metrics.matrixLookups shouldBeEqualTo 1
        result.metrics.invariants shouldBeEqualTo listOf("O(V*W+E)")
    }

    private fun input(
        workers: List<Worker> = listOf(worker()),
        visits: List<Visit> = listOf(visit("visit-1", CoordinateId("a"))),
        matrix: TravelTimeMatrix = this.matrix,
    ) = PlannerInput(
        workers = workers,
        visits = visits,
        matrix = matrix,
        datasetId = DatasetId("dataset-1"),
        planId = PlanId("plan-1"),
        planRevision = 1,
        requestGeneration = 1,
    )

    private fun worker(id: String = "worker-1", unavailable: Boolean = false) = Worker(
        workerId = WorkerId(id),
        name = "Synthetic $id",
        skills = setOf(skill),
        availability = listOf(AvailabilityWindow(dayStart, dayEnd)),
        homeCoordinateId = CoordinateId("depot"),
        unavailable = unavailable,
    )

    private fun visit(
        id: String,
        coordinateId: CoordinateId,
        requiredSkill: Skill = skill,
        priority: VisitPriority = VisitPriority.NORMAL,
        windowStart: Instant = dayStart,
        windowEnd: Instant = dayEnd,
        serviceDuration: Duration = Duration.ofMinutes(15),
        pin: VisitPin? = null,
        started: Boolean = false,
    ) = Visit(
        visitId = VisitId(id),
        coordinateId = coordinateId,
        requiredSkill = requiredSkill,
        windowStart = windowStart,
        windowEnd = windowEnd,
        serviceDuration = serviceDuration,
        priority = priority,
        startedAt = if (started) dayStart else null,
        startedPin = if (started) pin else null,
        manualPin = if (started) null else pin,
    )
}
