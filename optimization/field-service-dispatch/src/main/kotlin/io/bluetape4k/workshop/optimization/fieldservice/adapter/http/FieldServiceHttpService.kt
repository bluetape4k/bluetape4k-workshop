package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.workshop.optimization.fieldservice.application.ApprovalResult
import io.bluetape4k.workshop.optimization.fieldservice.application.CommandResult
import io.bluetape4k.workshop.optimization.fieldservice.application.DispatchResult
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceApprovalService
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceCommandService
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceDispatchService
import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventKey
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceConflict
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceConflictCode
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEventType
import io.bluetape4k.workshop.optimization.fieldservice.domain.IdempotencyKey
import io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanProposal
import io.bluetape4k.workshop.optimization.fieldservice.domain.Visit
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Worker
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceCommand
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import io.bluetape4k.workshop.optimization.fieldservice.planner.DeterministicFieldServicePlanner
import io.bluetape4k.workshop.optimization.fieldservice.planner.PlannerInput
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration

/** REST 경계에서 synthetic 상태 변경과 deterministic replan을 연결합니다. */
class FieldServiceHttpService(
    private val repository: FieldServiceRepository,
    private val commandService: FieldServiceCommandService,
    private val planner: DeterministicFieldServicePlanner,
    private val approvalService: FieldServiceApprovalService,
    private val dispatchService: FieldServiceDispatchService,
    private val canonicalizer: FieldServiceCanonicalizer = FieldServiceCanonicalizer(),
) {
    fun createVisit(request: CreateVisitRequest, idempotencyKey: String): CommandResult {
        val visit = Visit(
            visitId = VisitId(request.visitId),
            coordinateId = CoordinateId(request.coordinateId),
            requiredSkill = io.bluetape4k.workshop.optimization.fieldservice.domain.Skill(request.requiredSkill),
            windowStart = request.windowStart,
            windowEnd = request.windowEnd,
            serviceDuration = Duration.ofSeconds(request.serviceDurationSeconds),
            priority = request.priority,
        )
        return transaction {
            val command = command(
                visit.visitId.value,
                FieldServiceEventType.VISIT_CREATED,
                idempotencyKey,
                createPayload(request),
                repository.nextAggregateVersion("field_service", visit.visitId.value),
            )
            commandService.accept(command) {
                if (repository.findVisit(visit.visitId) != null) {
                    throw FieldServiceConflict(FieldServiceConflictCode.EVENT_KEY_REUSED, "visit already exists")
                }
                repository.saveVisit(visit)
            }
        }
    }

    fun mutateVisit(
        visitId: VisitId,
        eventType: FieldServiceEventType,
        idempotencyKey: String,
        payload: String = eventType.name,
        transform: (Visit) -> Visit,
    ): CommandResult = transaction {
        val current = repository.findVisit(visitId) ?: throw NoSuchElementException("visit not found")
        val next = transform(current)
        val command = command(
            aggregateId = visitId.value,
            eventType = eventType,
            idempotencyKey = idempotencyKey,
            payload = payload,
            expectedVersion = repository.nextAggregateVersion("field_service", visitId.value),
            eventVersion = next.version,
        )
        commandService.accept(command) {
            if (!repository.updateVisitIfVersion(visitId, current.version, next)) {
                throw FieldServiceConflict(FieldServiceConflictCode.VERSION_CONFLICT)
            }
        }
    }

    fun mutateWorker(
        workerId: WorkerId,
        idempotencyKey: String,
        payload: String = FieldServiceEventType.WORKER_UNAVAILABLE.name,
        transform: (Worker) -> Worker,
    ): CommandResult = transaction {
        val current = repository.findWorker(workerId) ?: throw NoSuchElementException("worker not found")
        val next = transform(current)
        val command = command(
            aggregateId = workerId.value,
            eventType = FieldServiceEventType.WORKER_UNAVAILABLE,
            idempotencyKey = idempotencyKey,
            payload = payload,
            expectedVersion = repository.nextAggregateVersion("field_service", workerId.value),
            eventVersion = next.version,
        )
        commandService.accept(command) {
            if (!repository.updateWorkerIfVersion(workerId, current.version, next)) {
                throw FieldServiceConflict(FieldServiceConflictCode.VERSION_CONFLICT)
            }
        }
    }

    fun recordTravelTime(request: TravelTimeUpdateRequest, idempotencyKey: String): CommandResult {
        if (!io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits.isFiniteNonNegativeTravelTime(request.seconds)) {
            throw InvalidFieldServiceInput("travel time must be finite and non-negative")
        }
        val aggregate = "${request.fromCoordinateId}->${request.toCoordinateId}"
        return transaction {
            commandService.accept(
                command(
                    aggregate,
                    FieldServiceEventType.TRAVEL_TIME_UPDATED,
                    idempotencyKey,
                    request.toString(),
                    repository.nextAggregateVersion("field_service", aggregate),
                ),
            ) {
                repository.saveTravelTime(
                    CoordinateId(request.fromCoordinateId),
                    CoordinateId(request.toCoordinateId),
                    request.seconds,
                )
            }
        }
    }

    fun replan(request: ReplanRequest, idempotencyKey: String): PlanProposal = transaction {
        val visits = repository.findVisits()
        val workers = repository.findWorkers()
        val coordinates = (visits.map { it.coordinateId } + workers.mapNotNull { it.homeCoordinateId }).toSet()
        val planId = PlanId(request.planId)
        val planHistory = repository.listPlans(planId)
        val revision = (planHistory.maxOfOrNull { it.planRevision } ?: -1L) + 1L
        val parent = planHistory.maxOfOrNull { it.planRevision }
        val command = command(
            aggregateId = planId.value,
            eventType = FieldServiceEventType.PLAN_REPLANNED,
            idempotencyKey = idempotencyKey,
            payload = "${request.planId}|${request.datasetId}",
            expectedVersion = repository.nextAggregateVersion("field_service", planId.value),
            eventVersion = revision,
        )
        var created: PlanProposal? = null
        return@transaction when (commandService.accept(command) {
            val plan = planner.plan(
                PlannerInput(
                    workers = workers,
                    visits = visits,
                    matrix = repository.currentTravelTimeMatrix(coordinates),
                    datasetId = DatasetId(request.datasetId),
                    planId = planId,
                    planRevision = revision,
                    parentRevision = parent,
                    requestGeneration = revision,
                ),
            )
            repository.savePlan(plan)
            created = plan
        }) {
            CommandResult.APPLIED -> requireNotNull(created)
            CommandResult.DUPLICATE -> {
                val stored = repository.findStoredEvent("field_service", planId.value, idempotencyKey)
                    ?: throw FieldServiceConflict(FieldServiceConflictCode.EVENT_KEY_REUSED)
                repository.loadPlan(planId, stored.aggregateVersion)
                    ?: throw FieldServiceConflict(FieldServiceConflictCode.STALE_REVISION)
            }
            CommandResult.EVENT_KEY_REUSED -> throw FieldServiceConflict(FieldServiceConflictCode.EVENT_KEY_REUSED)
            CommandResult.VERSION_CONFLICT -> throw FieldServiceConflict(FieldServiceConflictCode.VERSION_CONFLICT)
        }
    }

    fun approve(planId: PlanId, revision: Long, idempotencyKey: String): ApprovalResult = transaction {
        val plan = repository.loadPlan(planId, revision) ?: throw NoSuchElementException("plan not found")
        val command = command(
            aggregateId = planId.value,
            eventType = FieldServiceEventType.PLAN_APPROVED,
            idempotencyKey = idempotencyKey,
            payload = "${planId.value}|$revision",
            expectedVersion = repository.nextAggregateVersion("field_service", planId.value),
            eventVersion = revision,
        )
        return@transaction when (commandService.accept(command) {
            if (approvalService.approve(planId, revision, plan.versionVector) != ApprovalResult.APPROVED) {
                throw FieldServiceConflict(FieldServiceConflictCode.VERSION_CONFLICT)
            }
        }) {
            CommandResult.APPLIED -> ApprovalResult.APPROVED
            CommandResult.DUPLICATE -> if (repository.loadPlan(planId, revision)?.state == io.bluetape4k.workshop.optimization.fieldservice.domain.PlanState.APPROVED) {
                ApprovalResult.APPROVED
            } else {
                ApprovalResult.VERSION_CONFLICT
            }
            CommandResult.EVENT_KEY_REUSED -> throw FieldServiceConflict(FieldServiceConflictCode.EVENT_KEY_REUSED)
            CommandResult.VERSION_CONFLICT -> ApprovalResult.VERSION_CONFLICT
        }
    }

    fun confirm(workerId: WorkerId, planId: PlanId, revision: Long, idempotencyKey: String): DispatchResult = transaction {
        val command = command(
            aggregateId = "${planId.value}:${workerId.value}",
            eventType = FieldServiceEventType.ROUTE_CONFIRMED,
            idempotencyKey = idempotencyKey,
            payload = "${planId.value}|$revision|${workerId.value}",
            expectedVersion = repository.nextAggregateVersion("field_service", "${planId.value}:${workerId.value}"),
            eventVersion = revision,
        )
        return@transaction when (commandService.accept(command) {
            when (val result = dispatchService.confirmWorkerRoute(workerId, planId, revision)) {
                DispatchResult.COMMITTED -> Unit
                DispatchResult.VERSION_CONFLICT -> throw FieldServiceConflict(FieldServiceConflictCode.VERSION_CONFLICT)
                DispatchResult.SCHEDULE_CONFLICT -> throw FieldServiceConflict(FieldServiceConflictCode.SCHEDULE_CONFLICT)
            }
        }) {
            CommandResult.APPLIED -> DispatchResult.COMMITTED
            CommandResult.DUPLICATE -> if (repository.hasCommittedRoute(workerId, planId, revision)) {
                DispatchResult.COMMITTED
            } else {
                DispatchResult.VERSION_CONFLICT
            }
            CommandResult.EVENT_KEY_REUSED -> throw FieldServiceConflict(FieldServiceConflictCode.EVENT_KEY_REUSED)
            CommandResult.VERSION_CONFLICT -> DispatchResult.VERSION_CONFLICT
        }
    }

    fun command(
        aggregateId: String,
        eventType: FieldServiceEventType,
        idempotencyKey: String,
        payload: String,
        expectedVersion: Long,
        eventVersion: Long = expectedVersion,
    ): FieldServiceCommand {
        val key = IdempotencyKey(idempotencyKey)
        val digest = canonicalizer.digest("{\"payload\":${quote(payload)}}".toByteArray(UTF_8))
        return FieldServiceCommand(
            aggregateType = "field_service",
            aggregateId = AggregateId(aggregateId),
            eventKey = EventKey(key.value),
            eventType = eventType,
            digest = digest,
            payloadSummary = payload.take(240),
            expectedVersion = expectedVersion,
            eventVersion = eventVersion,
        )
    }

    private fun quote(value: String): String = tools.jackson.databind.json.JsonMapper.builder()
        .build()
        .writeValueAsString(value)

    private fun createPayload(request: CreateVisitRequest): String =
        listOf(
            request.visitId,
            request.coordinateId,
            request.requiredSkill,
            request.windowStart,
            request.windowEnd,
            request.serviceDurationSeconds,
            request.priority,
        ).joinToString("|").let(::boundedPayload)

    private fun boundedPayload(raw: String): String = if (raw.length <= io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits.MAX_STRING_LENGTH) {
        raw
    } else {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        "sha256:$digest"
    }
}
