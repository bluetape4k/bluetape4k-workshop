package io.bluetape4k.workshop.optimization.fieldservice.persistence

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigest
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigestMatch
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEvents
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanProposal
import io.bluetape4k.workshop.optimization.fieldservice.domain.VersionVector
import io.bluetape4k.workshop.optimization.fieldservice.domain.Visit
import io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId
import io.bluetape4k.workshop.optimization.fieldservice.domain.Worker
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import io.bluetape4k.workshop.optimization.fieldservice.planner.CoordinatePair
import io.bluetape4k.workshop.optimization.fieldservice.planner.TravelTimeMatrix
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * disposable Field Service workshop schema를 위한 Exposed repository입니다.
 *
 * plan projection의 일반 CRUD/read는 Bluetape [LongJdbcRepository]를 사용하고,
 * CAS, row lock, event append, outbox lease/fencing은 동시성 계약을 보존해야 하므로
 * 명시적인 Exposed SQL로 유지합니다.
 */
class FieldServiceRepository(
    private val clock: Clock = Clock.systemUTC(),
    private val leaseTokenGenerator: () -> UUID = { Uuid.V4.nextUUID() },
) : LongJdbcRepository<FieldServicePlanRecord> {
    override val table = FieldServicePlansTable

    override fun extractId(entity: FieldServicePlanRecord): Long = entity.id

    override fun ResultRow.toEntity(): FieldServicePlanRecord = FieldServicePlanRecord(
        id = this[FieldServicePlansTable.id].value,
        planId = this[FieldServicePlansTable.planId],
        planRevision = this[FieldServicePlansTable.planRevision],
        parentRevision = this[FieldServicePlansTable.parentRevision],
        datasetId = this[FieldServicePlansTable.datasetId],
        state = this[FieldServicePlansTable.state],
        payload = this[FieldServicePlansTable.payload],
        providerRequestId = this[FieldServicePlansTable.providerRequestId],
        providerRevision = this[FieldServicePlansTable.providerRevision],
        requestGeneration = this[FieldServicePlansTable.requestGeneration],
        createdAt = this[FieldServicePlansTable.createdAt],
    )

    fun saveWorker(worker: Worker): Worker {
        val payload = FieldServiceRecordCodec.encodeWorker(worker)
        FieldServiceWorkersTable.insert { statement ->
            statement[workerId] = worker.workerId.value
            statement[FieldServiceWorkersTable.payload] = payload
            statement[version] = worker.version
            statement[workerScheduleRevision] = worker.workerScheduleRevision
            statement[unavailable] = worker.unavailable
        }
        return worker
    }

    fun findWorker(workerId: WorkerId): Worker? =
        FieldServiceWorkersTable.selectAll()
            .where { FieldServiceWorkersTable.workerId eq workerId.value }
            .singleOrNull()
            ?.let { FieldServiceRecordCodec.decodeWorker(it[FieldServiceWorkersTable.payload]) }

    fun findWorkers(limit: Int = 100): List<Worker> {
        val bounded = limit.coerceIn(1, 100)
        return FieldServiceWorkersTable.selectAll()
            .orderBy(FieldServiceWorkersTable.workerId to SortOrder.ASC)
            .limit(bounded)
            .map { FieldServiceRecordCodec.decodeWorker(it[FieldServiceWorkersTable.payload]) }
    }

    fun saveVisit(visit: Visit): Visit {
        val payload = FieldServiceRecordCodec.encodeVisit(visit)
        FieldServiceVisitsTable.insert { statement ->
            statement[visitId] = visit.visitId.value
            statement[FieldServiceVisitsTable.payload] = payload
            statement[version] = visit.version
        }
        return visit
    }

    /** sparse edge 하나를 추가하고 matrix revision을 원자적으로 증가시킵니다. */
    fun saveTravelTime(from: CoordinateId, to: CoordinateId, seconds: Long): TravelTimeMatrix {
        require(FieldServiceLimits.isFiniteNonNegativeTravelTime(seconds)) { "travel time must be finite and non-negative" }
        val currentRevision = FieldServiceTravelTimesTable.selectAll()
            .orderBy(FieldServiceTravelTimesTable.matrixRevision to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(FieldServiceTravelTimesTable.matrixRevision)
            ?: 0L
        val nextRevision = currentRevision + 1L
        FieldServiceTravelTimesTable.insert { statement ->
            statement[matrixRevision] = nextRevision
            statement[fromCoordinateId] = from.value
            statement[toCoordinateId] = to.value
            statement[travelSeconds] = seconds
            statement[updatedAt] = clock.instant()
        }
        return currentTravelTimeMatrix(setOf(from, to))
    }

    /** edge별 최신 값을 읽고 가장 높은 matrix revision을 유지합니다. */
    fun currentTravelTimeMatrix(coordinateIds: Set<CoordinateId> = emptySet()): TravelTimeMatrix {
        val rows = FieldServiceTravelTimesTable.selectAll()
            .orderBy(FieldServiceTravelTimesTable.matrixRevision to SortOrder.DESC)
            .toList()
        val latest = linkedMapOf<CoordinatePair, Long>()
        rows.forEach { row ->
            val pair = CoordinatePair(
                CoordinateId(row[FieldServiceTravelTimesTable.fromCoordinateId]),
                CoordinateId(row[FieldServiceTravelTimesTable.toCoordinateId]),
            )
            latest.putIfAbsent(pair, row[FieldServiceTravelTimesTable.travelSeconds])
        }
        val storedCoordinates = latest.keys.flatMapTo(mutableSetOf()) { setOf(it.from, it.to) }
        return TravelTimeMatrix(
            revision = rows.firstOrNull()?.get(FieldServiceTravelTimesTable.matrixRevision) ?: 0L,
            coordinateIds = coordinateIds + storedCoordinates,
            edges = latest,
        )
    }

    fun findVisit(visitId: VisitId): Visit? =
        FieldServiceVisitsTable.selectAll()
            .where { FieldServiceVisitsTable.visitId eq visitId.value }
            .singleOrNull()
            ?.let { FieldServiceRecordCodec.decodeVisit(it[FieldServiceVisitsTable.payload]) }

    fun findVisits(limit: Int = 100): List<Visit> {
        val bounded = limit.coerceIn(1, 100)
        return FieldServiceVisitsTable.selectAll()
            .orderBy(FieldServiceVisitsTable.visitId to SortOrder.ASC)
            .limit(bounded)
            .map { FieldServiceRecordCodec.decodeVisit(it[FieldServiceVisitsTable.payload]) }
    }

    fun updateVisitIfVersion(id: VisitId, expectedVersion: Long, next: Visit): Boolean {
        val payload = FieldServiceRecordCodec.encodeVisit(next)
        val updated = FieldServiceVisitsTable.update(
            where = {
                (FieldServiceVisitsTable.visitId eq id.value) and
                    (FieldServiceVisitsTable.version eq expectedVersion)
            },
        ) { statement ->
            statement[FieldServiceVisitsTable.payload] = payload
            statement[version] = next.version
        }
        return updated == 1
    }

    /** worker business state CAS이며 schedule revision은 의도적으로 독립적입니다. */
    fun updateWorkerIfVersion(id: WorkerId, expectedVersion: Long, next: Worker): Boolean {
        val payload = FieldServiceRecordCodec.encodeWorker(next)
        val updated = FieldServiceWorkersTable.update(
            where = {
                (FieldServiceWorkersTable.workerId eq id.value) and
                    (FieldServiceWorkersTable.version eq expectedVersion)
            },
        ) { statement ->
            statement[FieldServiceWorkersTable.payload] = payload
            statement[version] = next.version
            statement[workerScheduleRevision] = next.workerScheduleRevision
            statement[unavailable] = next.unavailable
        }
        return updated == 1
    }

    fun savePlan(plan: PlanProposal): PlanProposal {
        val payload = FieldServiceRecordCodec.encode(plan)
        val now = clock.instant()
        FieldServicePlansTable.insert { statement ->
            statement[planId] = plan.planId.value
            statement[planRevision] = plan.planRevision
            statement[parentRevision] = plan.parentRevision
            statement[datasetId] = plan.datasetId.value
            statement[state] = StoredPlanState.valueOf(plan.state.name)
            statement[FieldServicePlansTable.payload] = payload
            statement[providerRequestId] = plan.providerRequestId?.value
            statement[providerRevision] = plan.providerRevision
            statement[requestGeneration] = plan.requestGeneration
            statement[createdAt] = now
        }
        plan.routes.forEach { route ->
            route.visits.forEach { planned ->
                FieldServicePlanAssignmentsTable.insert { statement ->
                    statement[planId] = plan.planId.value
                    statement[planRevision] = plan.planRevision
                    statement[workerId] = route.workerId.value
                    statement[visitId] = planned.visitId.value
                    statement[routeOrder] = planned.routeOrder
                    statement[visitVersion] = plan.versionVector.visitVersions[planned.visitId] ?: 0L
                    statement[workerVersion] = plan.versionVector.workerVersions[route.workerId] ?: 0L
                    statement[workerScheduleRevision] = plan.versionVector.workerScheduleRevisions[route.workerId] ?: 0L
                    statement[stale] = false
                }
            }
        }
        return plan
    }

    /** business revision 정렬은 보존하고, plan 조회는 Bluetape generic repository에 위임합니다. */
    fun loadPlan(planId: PlanId, revision: Long): PlanProposal? =
        findByField(FieldServicePlansTable.planId, planId.value)
            .singleOrNull { it.planRevision == revision }
            ?.let { FieldServiceRecordCodec.decodePlan(it.payload) }

    fun listPlans(limit: Int = 100): List<PlanProposal> {
        val bounded = limit.coerceIn(1, 100)
        return FieldServicePlansTable.selectAll()
            .orderBy(FieldServicePlansTable.planRevision to SortOrder.DESC)
            .limit(bounded)
            .map { FieldServiceRecordCodec.decodePlan(it[FieldServicePlansTable.payload]) }
    }

    fun listPlans(planId: PlanId, limit: Int = FieldServiceLimits.MAX_PLAN_HISTORY): List<PlanProposal> {
        val bounded = limit.coerceIn(1, FieldServiceLimits.MAX_PLAN_HISTORY)
        return findByField(FieldServicePlansTable.planId, planId.value)
            .sortedByDescending { it.planRevision }
            .take(bounded)
            .map { FieldServiceRecordCodec.decodePlan(it.payload) }
    }

    /** 두 set-based predicate로 모든 expected source row를 잠급니다. */
    fun lockVersionVector(vector: VersionVector): Boolean {
        val visitPredicate = vector.visitVersions.entries
            .map { (visitId, expected) ->
                (FieldServiceVisitsTable.visitId eq visitId.value) and
                    (FieldServiceVisitsTable.version eq expected)
            }
            .reduceOrNull { left, right -> left or right }
            ?: org.jetbrains.exposed.v1.core.Op.FALSE
        val visitRows = FieldServiceVisitsTable.selectAll()
            .where { visitPredicate }
            .forUpdate()
            .toList()
        if (visitRows.size != vector.visitVersions.size) return false

        val workerPredicate = vector.workerVersions.entries
            .mapNotNull { (workerId, expected) ->
                val expectedSchedule = vector.workerScheduleRevisions[workerId] ?: return@mapNotNull null
                (FieldServiceWorkersTable.workerId eq workerId.value) and
                    (FieldServiceWorkersTable.version eq expected) and
                    (FieldServiceWorkersTable.workerScheduleRevision eq expectedSchedule)
            }
            .reduceOrNull { left, right -> left or right }
            ?: org.jetbrains.exposed.v1.core.Op.FALSE
        val workerRows = FieldServiceWorkersTable.selectAll()
            .where { workerPredicate }
            .forUpdate()
            .toList()
        return workerRows.size == vector.workerVersions.size
    }

    /** redacted local projection만 다시 쓰는 proposal state CAS입니다. */
    fun updatePlanStateIfDraft(plan: PlanProposal, nextState: io.bluetape4k.workshop.optimization.fieldservice.domain.PlanState): Boolean {
        val next = plan.copy(state = nextState)
        return FieldServicePlansTable.update(
            where = {
                (FieldServicePlansTable.planId eq plan.planId.value) and
                    (FieldServicePlansTable.planRevision eq plan.planRevision) and
                    (FieldServicePlansTable.state eq StoredPlanState.DRAFT)
            },
        ) { statement ->
            statement[state] = StoredPlanState.valueOf(nextState.name)
            statement[FieldServicePlansTable.payload] = FieldServiceRecordCodec.encode(next)
        } == 1
    }

    /** test와 read-only conflict 진단에 사용하는 exact version-vector 재검사입니다. */
    fun currentVersionVectorMatches(vector: VersionVector): Boolean {
        val visitRows = if (vector.visitVersions.isEmpty()) {
            emptyMap()
        } else {
            FieldServiceVisitsTable.selectAll()
                .where { FieldServiceVisitsTable.visitId inList vector.visitVersions.keys.map { it.value } }
                .associate { it[FieldServiceVisitsTable.visitId] to it[FieldServiceVisitsTable.version] }
        }
        if (vector.visitVersions.any { (id, version) -> visitRows[id.value] != version }) return false

        val workerRows = if (vector.workerVersions.isEmpty()) {
            emptyMap()
        } else {
            FieldServiceWorkersTable.selectAll()
                .where { FieldServiceWorkersTable.workerId inList vector.workerVersions.keys.map { it.value } }
                .associate {
                    it[FieldServiceWorkersTable.workerId] to
                        (it[FieldServiceWorkersTable.version] to it[FieldServiceWorkersTable.workerScheduleRevision])
                }
        }
        return vector.workerVersions.all { (id, version) ->
            val row = workerRows[id.value] ?: return@all false
            row.first == version && row.second == vector.workerScheduleRevisions[id]
        }
    }

    /** provider 또는 request payload를 보존하지 않고 짧은 decision record를 추가합니다. */
    fun appendAudit(
        aggregateType: String,
        aggregateId: String,
        decision: String,
        planRevision: Long? = null,
        summary: String = decision,
    ) {
        FieldServiceAuditsTable.insert { statement ->
            statement[FieldServiceAuditsTable.aggregateType] = aggregateType.take(64)
            statement[FieldServiceAuditsTable.aggregateId] = aggregateId.take(200)
            statement[FieldServiceAuditsTable.decision] = decision.take(64)
            statement[FieldServiceAuditsTable.planRevision] = planRevision
            statement[FieldServiceAuditsTable.summary] = summary.take(240)
            statement[createdAt] = clock.instant()
        }
    }

    fun countAudits(): Long = FieldServiceAuditsTable.selectAll().count()

    /** proposal이 아직 draft일 때만 state를 업데이트합니다. */
    fun commitWorkerRoute(workerId: WorkerId, planId: PlanId, revision: Long): RouteCommitResult {
        val plan = loadPlan(planId, revision) ?: return RouteCommitResult.VERSION_CONFLICT
        if (plan.state != io.bluetape4k.workshop.optimization.fieldservice.domain.PlanState.APPROVED) {
            return RouteCommitResult.VERSION_CONFLICT
        }
        val route = plan.routes.singleOrNull { it.workerId == workerId } ?: return RouteCommitResult.VERSION_CONFLICT
        if (route.visits.isEmpty()) return RouteCommitResult.VERSION_CONFLICT

        val visitIds = route.visits.map { it.visitId }
        if (visitIds.size != visitIds.toSet().size) return RouteCommitResult.VERSION_CONFLICT
        val expectedVisitVersions = visitIds.map { visitId ->
            visitId to (plan.versionVector.visitVersions[visitId] ?: return RouteCommitResult.VERSION_CONFLICT)
        }

        // assignment를 검사하기 전에 route source row를 모두 잠급니다. worker만 CAS하면
        // concurrent sick-call/urgent update가 stale route를 commit할 수 있습니다.
        val lockedVisits = expectedVisitVersions
            .sortedBy { it.first.value }
            .all { (visitId, expectedVersion) ->
                FieldServiceVisitsTable.update(
                    where = {
                        (FieldServiceVisitsTable.visitId eq visitId.value) and
                            (FieldServiceVisitsTable.version eq expectedVersion)
                    },
                ) { statement -> statement[version] = expectedVersion } == 1
            }
        if (!lockedVisits) return RouteCommitResult.VERSION_CONFLICT

        if (hasCommittedRoute(workerId)) return RouteCommitResult.SCHEDULE_CONFLICT
        val committedVisits = FieldServiceDispatchAssignmentsTable.selectAll()
            .where { FieldServiceDispatchAssignmentsTable.visitId inList visitIds.map { it.value } }
            .limit(1)
            .any()
        if (committedVisits) return RouteCommitResult.SCHEDULE_CONFLICT

        val expectedWorkerVersion = plan.versionVector.workerVersions[workerId]
            ?: return RouteCommitResult.VERSION_CONFLICT
        val expectedScheduleRevision = plan.versionVector.workerScheduleRevisions[workerId]
            ?: return RouteCommitResult.SCHEDULE_CONFLICT
        val worker = findWorker(workerId) ?: return RouteCommitResult.VERSION_CONFLICT
        if (worker.version != expectedWorkerVersion ||
            worker.workerScheduleRevision != expectedScheduleRevision ||
            worker.unavailable
        ) {
            return RouteCommitResult.SCHEDULE_CONFLICT
        }
        val workerUpdated = FieldServiceWorkersTable.update(
            where = {
                (FieldServiceWorkersTable.workerId eq workerId.value) and
                    (FieldServiceWorkersTable.version eq expectedWorkerVersion) and
                    (FieldServiceWorkersTable.workerScheduleRevision eq expectedScheduleRevision) and
                    (FieldServiceWorkersTable.unavailable eq false)
            },
        ) { statement ->
            statement[workerScheduleRevision] = expectedScheduleRevision + 1
        }
        if (workerUpdated != 1) return RouteCommitResult.SCHEDULE_CONFLICT

        route.visits.chunked(100).forEach { chunk ->
            FieldServiceDispatchAssignmentsTable.batchInsert(chunk) { planned ->
                this[FieldServiceDispatchAssignmentsTable.visitId] = planned.visitId.value
                this[FieldServiceDispatchAssignmentsTable.workerId] = workerId.value
                this[FieldServiceDispatchAssignmentsTable.planId] = planId.value
                this[FieldServiceDispatchAssignmentsTable.planRevision] = revision
                this[FieldServiceDispatchAssignmentsTable.routeOrder] = planned.routeOrder
                this[FieldServiceDispatchAssignmentsTable.committedAt] = clock.instant()
            }
        }
        FieldServicePlanAssignmentsTable.update(
            where = {
                (FieldServicePlanAssignmentsTable.workerId eq workerId.value) and
                    (FieldServicePlanAssignmentsTable.workerScheduleRevision less expectedScheduleRevision + 1) and
                    (FieldServicePlanAssignmentsTable.stale eq false)
            },
        ) { statement -> statement[stale] = true }
        appendAudit("worker", workerId.value, "DISPATCH_COMMITTED", revision, "route_committed:${route.visits.size}")
        return RouteCommitResult.COMMITTED
    }

    fun hasCommittedRoute(workerId: WorkerId): Boolean =
        FieldServiceDispatchAssignmentsTable.selectAll()
            .where { FieldServiceDispatchAssignmentsTable.workerId eq workerId.value }
            .limit(1)
            .any()

    fun hasCommittedRoute(workerId: WorkerId, planId: PlanId, revision: Long): Boolean =
        FieldServiceDispatchAssignmentsTable.selectAll()
            .where {
                (FieldServiceDispatchAssignmentsTable.workerId eq workerId.value) and
                    (FieldServiceDispatchAssignmentsTable.planId eq planId.value) and
                    (FieldServiceDispatchAssignmentsTable.planRevision eq revision)
            }
            .limit(1)
            .any()

    fun findStoredEvent(aggregateType: String, aggregateId: String, eventKey: String): StoredFieldServiceEvent? =
        FieldServiceEventsTable.selectAll()
            .where {
                (FieldServiceEventsTable.aggregateType eq aggregateType) and
                    (FieldServiceEventsTable.aggregateId eq aggregateId) and
                    (FieldServiceEventsTable.eventKey eq eventKey)
            }
            .singleOrNull()
            ?.let {
                StoredFieldServiceEvent(
                    digest = EventDigest(it[FieldServiceEventsTable.digest]),
                    aggregateVersion = it[FieldServiceEventsTable.aggregateVersion],
                )
            }

    /** aggregate별 다음 event sequence를 읽습니다. 호출자는 이 값을 CAS expectedVersion으로 사용합니다. */
    fun nextAggregateVersion(aggregateType: String, aggregateId: String): Long =
        (FieldServiceEventsTable.selectAll()
            .where {
                (FieldServiceEventsTable.aggregateType eq aggregateType) and
                    (FieldServiceEventsTable.aggregateId eq aggregateId)
            }
            .orderBy(FieldServiceEventsTable.sequenceVersion to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(FieldServiceEventsTable.sequenceVersion)
            ?: -1L) + 1L

    fun appendEvent(command: FieldServiceCommand): EventAppendResult {
        val existing = FieldServiceEventsTable.selectAll()
            .where {
                (FieldServiceEventsTable.aggregateType eq command.aggregateType) and
                    (FieldServiceEventsTable.aggregateId eq command.aggregateId.value) and
                    (FieldServiceEventsTable.eventKey eq command.eventKey.value)
            }
            .singleOrNull()
        if (existing != null) {
            val storedDigest = EventDigest(existing[FieldServiceEventsTable.digest])
            return when (FieldServiceEvents.compare(storedDigest, command.digest)) {
                EventDigestMatch.DUPLICATE -> EventAppendResult.DUPLICATE
                EventDigestMatch.EVENT_KEY_REUSED -> EventAppendResult.EVENT_KEY_REUSED
            }
        }

        if (command.expectedVersion != nextAggregateVersion(command.aggregateType, command.aggregateId.value)) {
            return EventAppendResult.VERSION_CONFLICT
        }

        val inserted = FieldServiceEventsTable.insertIgnore { statement ->
            statement[aggregateType] = command.aggregateType
            statement[aggregateId] = command.aggregateId.value
            statement[eventKey] = command.eventKey.value
            statement[eventType] = command.eventType
            statement[digest] = command.digest.value
            statement[payloadSummary] = command.payloadSummary.take(240)
            statement[aggregateVersion] = command.eventVersion
            statement[sequenceVersion] = command.expectedVersion
            statement[createdAt] = clock.instant()
        }.insertedCount > 0
        if (inserted) return EventAppendResult.APPENDED

        val raced = FieldServiceEventsTable.selectAll()
            .where {
                (FieldServiceEventsTable.aggregateType eq command.aggregateType) and
                    (FieldServiceEventsTable.aggregateId eq command.aggregateId.value) and
                    (FieldServiceEventsTable.eventKey eq command.eventKey.value)
            }
            .singleOrNull()
            ?: return EventAppendResult.VERSION_CONFLICT
        val storedDigest = EventDigest(raced[FieldServiceEventsTable.digest])
        return when (FieldServiceEvents.compare(storedDigest, command.digest)) {
            EventDigestMatch.DUPLICATE -> EventAppendResult.DUPLICATE
            EventDigestMatch.EVENT_KEY_REUSED -> EventAppendResult.EVENT_KEY_REUSED
        }
    }

    fun countEvents(): Long = FieldServiceEventsTable.selectAll().count()

    fun enqueueOutbox(record: OutboxRecord): OutboxRecord {
        require(record.maxAttempts in 1..5) { "outbox maxAttempts must be 1..5" }
        val id = FieldServiceOutboxTable.insert { statement ->
            statement[payload] = record.payload
            statement[status] = record.status
            statement[attempt] = record.attempt
            statement[maxAttempts] = record.maxAttempts
            statement[nextAttemptAt] = record.nextAttemptAt
            statement[leaseOwner] = record.leaseOwner
            statement[leaseToken] = record.leaseToken
            statement[leaseExpiresAt] = record.leaseExpiresAt
            statement[lastError] = record.lastError?.take(240)
            statement[createdAt] = record.createdAt
        } get FieldServiceOutboxTable.id
        return record.copy(id = id)
    }

    fun claimOutbox(limit: Int = 10, owner: String = "field-service-worker"): List<OutboxRecord> {
        val bounded = limit.coerceIn(1, 10)
        val now = clock.instant()
        val rows = FieldServiceOutboxTable.selectAll()
            .where {
                ((FieldServiceOutboxTable.status inList listOf(OutboxStatus.PENDING, OutboxStatus.RETRYABLE)) or
                    ((FieldServiceOutboxTable.status eq OutboxStatus.CLAIMED) and
                        (FieldServiceOutboxTable.leaseExpiresAt lessEq now))) and
                    (FieldServiceOutboxTable.nextAttemptAt lessEq now)
            }
            .orderBy(FieldServiceOutboxTable.id to SortOrder.ASC)
            .limit(bounded)
            .toList()
        return rows.mapNotNull { row ->
            // lease token은 DB 정렬이 필요 없는 opaque 보안 값이므로 UUID v4를 사용합니다.
            val token = leaseTokenGenerator().toString()
            val changed = FieldServiceOutboxTable.update(
                where = {
                    (FieldServiceOutboxTable.id eq row[FieldServiceOutboxTable.id]) and
                        (((FieldServiceOutboxTable.status inList listOf(OutboxStatus.PENDING, OutboxStatus.RETRYABLE)) or
                            ((FieldServiceOutboxTable.status eq OutboxStatus.CLAIMED) and
                                (FieldServiceOutboxTable.leaseExpiresAt lessEq now)))
                        )
                },
            ) { statement ->
                statement[status] = OutboxStatus.CLAIMED
                statement[leaseOwner] = owner
                statement[leaseToken] = token
                statement[leaseExpiresAt] = now.plusSeconds(30)
            }
            if (changed != 1) null else rowToOutbox(
                row = FieldServiceOutboxTable.selectAll()
                    .where { FieldServiceOutboxTable.id eq row[FieldServiceOutboxTable.id] }
                    .single(),
            )
        }
    }

    fun completeOutbox(id: Long, owner: String, token: String): Boolean =
        FieldServiceOutboxTable.update(
            where = {
                (FieldServiceOutboxTable.id eq id) and
                    (FieldServiceOutboxTable.status eq OutboxStatus.CLAIMED) and
                    (FieldServiceOutboxTable.leaseOwner eq owner) and
                    (FieldServiceOutboxTable.leaseToken eq token) and
                    (FieldServiceOutboxTable.leaseExpiresAt greater clock.instant())
            },
        ) { statement ->
            statement[status] = OutboxStatus.COMPLETED
            statement[leaseOwner] = null
            statement[leaseToken] = null
            statement[leaseExpiresAt] = null
        } == 1

    fun retryOutbox(
        id: Long,
        owner: String,
        token: String,
        error: String,
    ): Boolean {
        val row = FieldServiceOutboxTable.selectAll()
            .where { FieldServiceOutboxTable.id eq id }
            .singleOrNull() ?: return false
        val nextAttempt = row[FieldServiceOutboxTable.attempt] + 1
        val terminal = nextAttempt >= row[FieldServiceOutboxTable.maxAttempts]
        val delaySeconds = (1L shl nextAttempt.coerceAtMost(6)).coerceAtMost(60L)
        return FieldServiceOutboxTable.update(
            where = {
                (FieldServiceOutboxTable.id eq id) and
                    (FieldServiceOutboxTable.status eq OutboxStatus.CLAIMED) and
                    (FieldServiceOutboxTable.leaseOwner eq owner) and
                    (FieldServiceOutboxTable.leaseToken eq token) and
                    (FieldServiceOutboxTable.leaseExpiresAt greater clock.instant())
            },
        ) { statement ->
            statement[status] = if (terminal) OutboxStatus.DEAD_LETTER else OutboxStatus.RETRYABLE
            statement[attempt] = nextAttempt
            statement[nextAttemptAt] = clock.instant().plusSeconds(delaySeconds)
            statement[leaseOwner] = null
            statement[leaseToken] = null
            statement[leaseExpiresAt] = null
            statement[lastError] = error.take(240)
        } == 1
    }

    fun deadLetterOutbox(id: Long, owner: String, token: String, error: String): Boolean =
        FieldServiceOutboxTable.update(
            where = {
                (FieldServiceOutboxTable.id eq id) and
                    (FieldServiceOutboxTable.status eq OutboxStatus.CLAIMED) and
                    (FieldServiceOutboxTable.leaseOwner eq owner) and
                    (FieldServiceOutboxTable.leaseToken eq token) and
                    (FieldServiceOutboxTable.leaseExpiresAt greater clock.instant())
            },
        ) { statement ->
            statement[status] = OutboxStatus.DEAD_LETTER
            statement[leaseOwner] = null
            statement[leaseToken] = null
            statement[leaseExpiresAt] = null
            statement[lastError] = error.take(240)
        } == 1

    fun renewOutbox(id: Long, owner: String, token: String, leaseSeconds: Long = 30): Boolean =
        FieldServiceOutboxTable.update(
            where = {
                (FieldServiceOutboxTable.id eq id) and
                    (FieldServiceOutboxTable.status eq OutboxStatus.CLAIMED) and
                    (FieldServiceOutboxTable.leaseOwner eq owner) and
                    (FieldServiceOutboxTable.leaseToken eq token) and
                    (FieldServiceOutboxTable.leaseExpiresAt greater clock.instant())
            },
        ) { statement ->
            statement[leaseExpiresAt] = clock.instant().plusSeconds(leaseSeconds)
        } == 1

    fun countAssignments(): Long = FieldServiceDispatchAssignmentsTable.selectAll().count()

    private fun rowToOutbox(row: org.jetbrains.exposed.v1.core.ResultRow): OutboxRecord = OutboxRecord(
        id = row[FieldServiceOutboxTable.id],
        payload = row[FieldServiceOutboxTable.payload],
        status = row[FieldServiceOutboxTable.status],
        attempt = row[FieldServiceOutboxTable.attempt],
        maxAttempts = row[FieldServiceOutboxTable.maxAttempts],
        nextAttemptAt = row[FieldServiceOutboxTable.nextAttemptAt],
        leaseOwner = row[FieldServiceOutboxTable.leaseOwner],
        leaseToken = row[FieldServiceOutboxTable.leaseToken],
        leaseExpiresAt = row[FieldServiceOutboxTable.leaseExpiresAt],
        lastError = row[FieldServiceOutboxTable.lastError],
        createdAt = row[FieldServiceOutboxTable.createdAt],
    )
}

enum class RouteCommitResult {
    COMMITTED,
    VERSION_CONFLICT,
    SCHEDULE_CONFLICT,
}
