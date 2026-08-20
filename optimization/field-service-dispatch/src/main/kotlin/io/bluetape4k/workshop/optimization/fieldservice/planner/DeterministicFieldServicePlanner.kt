package io.bluetape4k.workshop.optimization.fieldservice.planner

import io.bluetape4k.workshop.optimization.fieldservice.domain.ConstraintExplanation
import io.bluetape4k.workshop.optimization.fieldservice.domain.ConstraintReasonCode
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceScoreSummary
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanProposal
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlannerMetrics
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlannerRun
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlannedVisit
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanState
import io.bluetape4k.workshop.optimization.fieldservice.domain.VersionVector
import io.bluetape4k.workshop.optimization.fieldservice.domain.Visit
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitStatus
import io.bluetape4k.workshop.optimization.fieldservice.domain.Worker
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerRoute
import io.bluetape4k.workshop.optimization.fieldservice.domain.UnassignedVisit
import java.time.Duration
import java.time.Instant

/** 한 deterministic planning run을 위한 불변 input snapshot입니다. */
data class PlannerInput(
    val workers: List<Worker>,
    val visits: List<Visit>,
    val matrix: TravelTimeMatrix,
    val datasetId: DatasetId,
    val planId: io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId,
    val planRevision: Long = 0L,
    val parentRevision: Long? = null,
    val requestGeneration: Long = 0L,
) {
    init {
        require(workers.size <= FieldServiceLimits.MAX_WORKERS) { "workers exceed configured limit" }
        require(visits.size <= FieldServiceLimits.MAX_VISITS) { "visits exceed configured limit" }
        require(planRevision >= 0L && (parentRevision == null || parentRevision >= 0L)) { "invalid plan revision" }
    }
}

/**
 * synthetic 데이터만 대상으로 hard constraint를 적용하는 deterministic planner입니다.
 * 실제 provider 호출이나 route quality 보장은 이 예제의 범위가 아닙니다.
 */
class DeterministicFieldServicePlanner {
    fun plan(input: PlannerInput): PlanProposal = planWithMetrics(input).proposal

    fun planWithMetrics(input: PlannerInput): PlannerRun {
        val sortedWorkers = input.workers.sortedBy { it.workerId.value }
        val routes = sortedWorkers.associate { it.workerId to mutableListOf<MutablePlannedVisit>() }.toMutableMap()
        val workerAvailableAt = sortedWorkers.associate { it.workerId to it.availability.minOfOrNull { window -> window.start } }
            .toMutableMap()
        val nextRouteOrder = sortedWorkers.associate { it.workerId to 0 }.toMutableMap()
        var candidateEvaluations = 0
        var matrixLookups = 0
        val unassigned = mutableListOf<UnassignedVisit>()
        val explanations = mutableListOf<ConstraintExplanation>()
        val pinned = input.visits.filter { it.effectivePin != null }
            .sortedWith(compareBy({ it.effectivePin?.workerId?.value }, { it.effectivePin?.routeOrder }, { it.visitId.value }))
        val pinnedIds = pinned.mapTo(hashSetOf()) { it.visitId }
        val pinnedOrders = mutableMapOf<WorkerId, MutableSet<Int>>()

        pinned.forEach { visit ->
            val pin = visit.effectivePin ?: return@forEach
            val worker = sortedWorkers.firstOrNull { it.workerId == pin.workerId }
            val duplicateOrder = worker != null &&
                !pinnedOrders.getOrPut(worker.workerId) { mutableSetOf() }.add(pin.routeOrder)
            val previous = worker?.let { routes.getValue(it.workerId).lastOrNull()?.visit?.coordinateId }
            val from = previous ?: worker?.homeCoordinateId
            val travel = if (from == null) {
                0L
            } else {
                matrixLookups++
                input.matrix.lookup(from, visit.coordinateId)
            }
            val reason = when {
                worker == null -> ConstraintReasonCode.PIN_CONFLICT
                duplicateOrder -> ConstraintReasonCode.PIN_CONFLICT
                worker.unavailable -> ConstraintReasonCode.UNAVAILABLE
                visit.status != VisitStatus.UNASSIGNED && visit.status != VisitStatus.ASSIGNED -> statusReason(visit.status)
                visit.requiredSkill !in worker.skills -> ConstraintReasonCode.MISSING_SKILL
                travel == null -> ConstraintReasonCode.TRAVEL_TIME
                else -> null
            }
            val slot = if (reason == null && worker != null) {
                earliestFeasibleSlot(worker, workerAvailableAt[worker.workerId], visit, travel ?: 0L)
            } else {
                null
            }
            val arrival = slot?.first ?: visit.windowStart
            val departure = slot?.second ?: arrival.plus(visit.serviceDuration)
            val finalReason = reason ?: if (slot == null) ConstraintReasonCode.TIME_WINDOW else null
            if (finalReason != null) {
                unassigned += UnassignedVisit(visit.visitId, finalReason)
                explanations += ConstraintExplanation(visit.visitId, finalReason)
            } else {
                val eligibleWorker = worker ?: return@forEach
                routes.getValue(eligibleWorker.workerId) += MutablePlannedVisit(visit, pin.routeOrder, pinned = true)
                nextRouteOrder[eligibleWorker.workerId] = maxOf(
                    nextRouteOrder.getValue(eligibleWorker.workerId),
                    pin.routeOrder + 1,
                )
                workerAvailableAt[eligibleWorker.workerId] = departure
            }
        }

        input.visits.asSequence()
            .filter { it.visitId !in pinnedIds }
            .sortedWith(compareByDescending<Visit> { it.priority.name == "URGENT" }.thenBy { it.windowStart }.thenBy { it.visitId.value })
            .forEach { visit ->
                val terminalReason = when (visit.status) {
                    VisitStatus.CANCELLED -> ConstraintReasonCode.CANCELLED
                    VisitStatus.NO_SHOW -> ConstraintReasonCode.NO_SHOW
                    VisitStatus.COMPLETED -> ConstraintReasonCode.COMPLETED
                    else -> null
                }
                if (terminalReason != null) {
                    unassigned += UnassignedVisit(visit.visitId, terminalReason)
                    explanations += ConstraintExplanation(visit.visitId, terminalReason)
                    return@forEach
                }
                val result = assign(visit, sortedWorkers, routes, workerAvailableAt, input.matrix)
                candidateEvaluations += result.evaluations
                matrixLookups += result.lookups
                if (result.workerId == null) {
                    val reason = result.reason ?: ConstraintReasonCode.TIME_WINDOW
                    unassigned += UnassignedVisit(visit.visitId, reason)
                    explanations += ConstraintExplanation(visit.visitId, reason)
                } else {
                    val routeOrder = nextRouteOrder.getValue(result.workerId)
                    routes.getValue(result.workerId) += MutablePlannedVisit(visit, routeOrder, pinned = false)
                    nextRouteOrder[result.workerId] = routeOrder + 1
                    workerAvailableAt[result.workerId] = result.nextAvailableAt
                }
            }

        val outputRoutes = routes.entries
            .sortedBy { it.key.value }
            .map { (workerId, visits) ->
                WorkerRoute(
                    workerId,
                    visits.sortedWith(compareBy<MutablePlannedVisit> { it.routeOrder }.thenBy { it.visit.visitId.value })
                        .take(FieldServiceLimits.MAX_ROUTE_STOPS)
                        .map { planned ->
                            PlannedVisit(planned.visit.visitId, planned.visit.coordinateId, planned.routeOrder, planned.pinned)
                        },
                )
            }
        val assignedCount = outputRoutes.sumOf { it.visits.size }
        val reasons = unassigned.sortedBy { it.visitId.value }
        val score = FieldServiceScoreSummary(
            hardScore = assignedCount.toLong() * 1_000L,
            softScore = -outputRoutes.sumOf { route -> route.visits.zipWithNext().size }.toLong(),
            assignedCount = assignedCount,
            unassignedCount = reasons.size,
        )
        val versionVector = VersionVector(
            visitVersions = input.visits.associate { it.visitId to it.version },
            workerVersions = input.workers.associate { it.workerId to it.version },
            workerScheduleRevisions = input.workers.associate { it.workerId to it.workerScheduleRevision },
        )
        val proposal = PlanProposal(
            planId = input.planId,
            planRevision = input.planRevision,
            parentRevision = input.parentRevision,
            requestGeneration = input.requestGeneration,
            datasetId = input.datasetId,
            versionVector = versionVector,
            routes = outputRoutes,
            unassigned = reasons,
            score = score,
            explanations = explanations.sortedBy { it.visitId.value }.take(FieldServiceLimits.MAX_EXPLANATIONS),
            state = PlanState.DRAFT,
        )
        return PlannerRun(
            proposal = proposal,
            metrics = PlannerMetrics(candidateEvaluations, matrixLookups, externalCalls = 0),
        )
    }

    private fun assign(
        visit: Visit,
        workers: List<Worker>,
        routes: Map<WorkerId, MutableList<MutablePlannedVisit>>,
        availableAt: MutableMap<WorkerId, Instant?>,
        matrix: TravelTimeMatrix,
    ): AssignmentResult {
        var sawSkill = false
        var sawUnavailable = false
        var sawTravel = false
        var sawTimeWindow = false
        var evaluations = 0
        var lookups = 0
        var best: Candidate? = null
        workers.forEach { worker ->
            evaluations++
            if (visit.requiredSkill !in worker.skills) return@forEach
            sawSkill = true
            if (worker.unavailable) {
                sawUnavailable = true
                return@forEach
            }
            val from = routes.getValue(worker.workerId).lastOrNull()?.visit?.coordinateId ?: worker.homeCoordinateId
            val travel = if (from == null) 0L else {
                lookups++
                matrix.lookup(from, visit.coordinateId)
            }
            if (travel == null) {
                sawTravel = true
                return@forEach
            }
            val slot = earliestFeasibleSlot(worker, availableAt[worker.workerId], visit, travel)
            if (slot == null) {
                sawTimeWindow = true
                return@forEach
            }
            val candidate = Candidate(worker.workerId, slot.first, slot.second, travel)
            val current = best
            if (current == null ||
                candidate.arrival < current.arrival ||
                (candidate.arrival == current.arrival && candidate.workerId.value < current.workerId.value)
            ) best = candidate
        }
        val reason = when {
            !sawSkill -> ConstraintReasonCode.MISSING_SKILL
            sawUnavailable && !sawTravel && !sawTimeWindow -> ConstraintReasonCode.UNAVAILABLE
            sawTravel && !sawTimeWindow -> ConstraintReasonCode.TRAVEL_TIME
            else -> ConstraintReasonCode.TIME_WINDOW
        }
        return AssignmentResult(best?.workerId, best?.departure ?: availableAt.values.firstOrNull() ?: Instant.EPOCH, reason, evaluations, lookups)
    }

    private fun advance(current: Instant?, duration: Duration): Instant? = current?.plus(duration)

    private fun maxInstant(first: Instant, second: Instant): Instant = if (first >= second) first else second

    /** 이후의 분리된 window로 대기하는 경우까지 포함해 첫 feasible interval을 찾습니다. */
    private fun earliestFeasibleSlot(
        worker: Worker,
        readyAt: Instant?,
        visit: Visit,
        travelSeconds: Long,
    ): Pair<Instant, Instant>? {
        val earliestArrival = maxInstant(
            readyAt?.plusSeconds(travelSeconds) ?: visit.windowStart,
            visit.windowStart,
        )
        return worker.availability.asSequence()
            .mapNotNull { window ->
                val arrival = maxInstant(earliestArrival, window.start)
                val departure = arrival.plus(visit.serviceDuration)
                if (departure.isAfter(visit.windowEnd) || departure.isAfter(window.end)) {
                    null
                } else {
                    arrival to departure
                }
            }
            .minByOrNull { it.first }
    }

    private fun statusReason(status: VisitStatus): ConstraintReasonCode = when (status) {
        VisitStatus.CANCELLED -> ConstraintReasonCode.CANCELLED
        VisitStatus.NO_SHOW -> ConstraintReasonCode.NO_SHOW
        VisitStatus.COMPLETED -> ConstraintReasonCode.COMPLETED
        else -> ConstraintReasonCode.PIN_CONFLICT
    }

    private data class MutablePlannedVisit(val visit: Visit, val routeOrder: Int, val pinned: Boolean)

    private data class Candidate(val workerId: WorkerId, val arrival: Instant, val departure: Instant, val travel: Long)

    private data class AssignmentResult(
        val workerId: WorkerId?,
        val nextAvailableAt: Instant,
        val reason: ConstraintReasonCode?,
        val evaluations: Int,
        val lookups: Int,
    )
}
