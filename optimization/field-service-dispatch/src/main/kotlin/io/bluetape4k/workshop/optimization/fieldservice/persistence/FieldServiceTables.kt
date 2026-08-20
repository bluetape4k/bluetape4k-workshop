package io.bluetape4k.workshop.optimization.fieldservice.persistence

import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/** worker eligibility와 schedule version의 source of truth입니다. */
object FieldServiceWorkersTable : Table("field_service_workers") {
    val id = long("id").autoIncrement()
    val workerId = varchar("worker_id", FieldServiceLimits.MAX_STRING_LENGTH).uniqueIndex()
    val payload = text("payload")
    val version = long("version")
    val workerScheduleRevision = long("worker_schedule_revision")
    val unavailable = bool("unavailable")
    override val primaryKey = PrimaryKey(id)
}

/** synthetic visit state와 business version의 source of truth입니다. */
object FieldServiceVisitsTable : Table("field_service_visits") {
    val id = long("id").autoIncrement()
    val visitId = varchar("visit_id", FieldServiceLimits.MAX_STRING_LENGTH).uniqueIndex()
    val payload = text("payload")
    val version = long("version")
    override val primaryKey = PrimaryKey(id)
}

/** 모든 planner snapshot이 사용하는 versioned sparse travel-time projection입니다. */
object FieldServiceTravelTimesTable : Table("field_service_travel_times") {
    val id = long("id").autoIncrement()
    val matrixRevision = long("matrix_revision")
    val fromCoordinateId = varchar("from_coordinate_id", FieldServiceLimits.MAX_STRING_LENGTH)
    val toCoordinateId = varchar("to_coordinate_id", FieldServiceLimits.MAX_STRING_LENGTH)
    val travelSeconds = long("travel_seconds")
    val updatedAt = timestamp("updated_at")
    init {
        uniqueIndex(matrixRevision, fromCoordinateId, toCoordinateId)
        index(false, fromCoordinateId, toCoordinateId, matrixRevision)
    }
    override val primaryKey = PrimaryKey(id)
}

/** 불변 plan proposal metadata와 redacted serialized proposal입니다. */
object FieldServicePlansTable : Table("field_service_plans") {
    val id = long("id").autoIncrement()
    val planId = varchar("plan_id", FieldServiceLimits.MAX_STRING_LENGTH)
    val planRevision = long("plan_revision")
    val parentRevision = long("parent_revision").nullable()
    val datasetId = varchar("dataset_id", FieldServiceLimits.MAX_STRING_LENGTH)
    val state = enumerationByName<StoredPlanState>("state", 24)
    val payload = text("payload")
    val providerRequestId = varchar("provider_request_id", FieldServiceLimits.MAX_STRING_LENGTH).nullable()
    val providerRevision = long("provider_revision").nullable()
    val requestGeneration = long("request_generation")
    val createdAt = timestamp("created_at")
    init {
        uniqueIndex(planId, planRevision)
        index(false, state, planRevision)
    }
    override val primaryKey = PrimaryKey(id)
}

/** proposal에 속한 불변 worker route row입니다. */
object FieldServicePlanAssignmentsTable : Table("field_service_plan_assignments") {
    val id = long("id").autoIncrement()
    val planId = varchar("plan_id", FieldServiceLimits.MAX_STRING_LENGTH)
    val planRevision = long("plan_revision")
    val workerId = varchar("worker_id", FieldServiceLimits.MAX_STRING_LENGTH)
    val visitId = varchar("visit_id", FieldServiceLimits.MAX_STRING_LENGTH)
    val routeOrder = integer("route_order")
    val visitVersion = long("visit_version")
    val workerVersion = long("worker_version")
    val workerScheduleRevision = long("worker_schedule_revision")
    val stale = bool("stale")
    init {
        uniqueIndex(planId, planRevision, workerId, routeOrder)
        index(false, workerId, workerScheduleRevision, routeOrder)
    }
    override val primaryKey = PrimaryKey(id)
}

/** authoritative committed assignment projection이며 방문별 current assignment 하나를 보유합니다. */
object FieldServiceDispatchAssignmentsTable : Table("field_service_dispatch_assignments") {
    val id = long("id").autoIncrement()
    val visitId = varchar("visit_id", FieldServiceLimits.MAX_STRING_LENGTH).uniqueIndex()
    val workerId = varchar("worker_id", FieldServiceLimits.MAX_STRING_LENGTH)
    val planId = varchar("plan_id", FieldServiceLimits.MAX_STRING_LENGTH)
    val planRevision = long("plan_revision")
    val routeOrder = integer("route_order")
    val committedAt = timestamp("committed_at")
    override val primaryKey = PrimaryKey(id)
}

/** canonical event idempotency log이며 provider raw body나 secret을 저장하지 않습니다. */
object FieldServiceEventsTable : Table("field_service_events") {
    val id = long("id").autoIncrement()
    val aggregateType = varchar("aggregate_type", 64)
    val aggregateId = varchar("aggregate_id", FieldServiceLimits.MAX_STRING_LENGTH)
    val eventKey = varchar("event_key", FieldServiceLimits.MAX_KEY_LENGTH)
    val eventType = enumerationByName<io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEventType>("event_type", 32)
    val digest = varchar("digest", 64)
    val payloadSummary = varchar("payload_summary", 240)
    val aggregateVersion = long("aggregate_version")
    val createdAt = timestamp("created_at")
    init {
        uniqueIndex(aggregateType, aggregateId, eventKey)
        index(false, aggregateType, aggregateId, digest)
    }
    override val primaryKey = PrimaryKey(id)
}

/** append-only redacted decision audit입니다. */
object FieldServiceAuditsTable : Table("field_service_audits") {
    val id = long("id").autoIncrement()
    val aggregateType = varchar("aggregate_type", 64)
    val aggregateId = varchar("aggregate_id", FieldServiceLimits.MAX_STRING_LENGTH)
    val decision = varchar("decision", 64)
    val planRevision = long("plan_revision").nullable()
    val summary = varchar("summary", 240)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** command/replan 작업의 bounded retry와 fencing state입니다. */
object FieldServiceOutboxTable : Table("field_service_outbox") {
    val id = long("id").autoIncrement()
    val payload = text("payload")
    val status = enumerationByName<OutboxStatus>("status", 24)
    val attempt = integer("attempt")
    val maxAttempts = integer("max_attempts")
    val nextAttemptAt = timestamp("next_attempt_at")
    val leaseOwner = varchar("lease_owner", 120).nullable()
    val leaseToken = varchar("lease_token", 120).nullable()
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val lastError = varchar("last_error", 240).nullable()
    val createdAt = timestamp("created_at")
    init {
        index(false, status, nextAttemptAt, id)
    }
    override val primaryKey = PrimaryKey(id)
}

enum class StoredPlanState {
    DRAFT,
    APPROVED,
    REJECTED,
    STALE,
}

enum class OutboxStatus {
    PENDING,
    CLAIMED,
    RETRYABLE,
    COMPLETED,
    DEAD_LETTER,
}

/** disposable workshop database fixture에서만 사용하는 ordered schema list입니다. */
object FieldServiceTables {
    val all: Array<Table> = arrayOf(
        FieldServiceWorkersTable,
        FieldServiceVisitsTable,
        FieldServiceTravelTimesTable,
        FieldServicePlansTable,
        FieldServicePlanAssignmentsTable,
        FieldServiceDispatchAssignmentsTable,
        FieldServiceEventsTable,
        FieldServiceAuditsTable,
        FieldServiceOutboxTable,
    )
}
