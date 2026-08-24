package io.bluetape4k.workshop.optimization.lastmile.persistence

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileLimits
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

private const val ID_LENGTH = 64
private const val PAYLOAD_LENGTH = 8_192

/** PostgreSQL 권위 aggregate입니다. 감사 컬럼은 bluetape4k-exposed가 관리합니다. */
object LastMileJobsTable : AuditableLongIdTable("last_mile_jobs") {
    val jobId = varchar("job_id", ID_LENGTH).uniqueIndex()
    val pickupCoordinateId = varchar("pickup_coordinate_id", ID_LENGTH)
    val deliveryCoordinateId = varchar("delivery_coordinate_id", ID_LENGTH)
    val demand = integer("demand")
    val pickupWindowStart = timestamp("pickup_window_start")
    val pickupWindowEnd = timestamp("pickup_window_end")
    val deliveryWindowStart = timestamp("delivery_window_start")
    val deliveryWindowEnd = timestamp("delivery_window_end")
    val requiredSkill = varchar("required_skill", 32).nullable()
    val priority = varchar("priority", 16)
    val status = varchar("status", 16)
    val carrierVersion = long("carrier_version")
}

object LastMileVehiclesTable : AuditableLongIdTable("last_mile_vehicles") {
    val vehicleId = varchar("vehicle_id", ID_LENGTH).uniqueIndex()
    val driverId = varchar("driver_id", ID_LENGTH)
    val depotCoordinateId = varchar("depot_coordinate_id", ID_LENGTH)
    val capacity = integer("capacity")
    val skills = varchar("skills", 512)
    val availableAt = timestamp("available_at")
    val startedJobId = varchar("started_job_id", ID_LENGTH).nullable()
    val startedKind = varchar("started_kind", 16).nullable()
    val startedCoordinateId = varchar("started_coordinate_id", ID_LENGTH).nullable()
    val startedSequence = integer("started_sequence").nullable()
    val startedAt = timestamp("started_at").nullable()
}

object LastMileMatrixRevisionsTable : LongIdTable("last_mile_matrix_revisions") {
    val revision = long("revision").uniqueIndex()
    val coordinateDigest = varchar("coordinate_digest", 64)
    val createdAt = timestamp("created_at")
}

object LastMileMatrixEdgesTable : LongIdTable("last_mile_matrix_edges") {
    val revision = long("revision")
    val fromCoordinateId = varchar("from_coordinate_id", ID_LENGTH)
    val toCoordinateId = varchar("to_coordinate_id", ID_LENGTH)
    val travelSeconds = long("travel_seconds")

    init {
        uniqueIndex(revision, fromCoordinateId, toCoordinateId)
    }
}

object LastMilePlansTable : AuditableLongIdTable("last_mile_plans") {
    val planId = varchar("plan_id", ID_LENGTH)
    val planRevision = long("plan_revision")
    val parentRevision = long("parent_revision").nullable()
    val requestGeneration = long("request_generation")
    val matrixRevision = long("matrix_revision")
    val providerRevision = long("provider_revision").nullable()
    val state = varchar("state", 16)
    val hardScore = long("hard_score")
    val softScore = long("soft_score")
    val assignedJobs = integer("assigned_jobs")
    val unassignedJobs = integer("unassigned_jobs")

    init {
        uniqueIndex(planId, planRevision)
    }
}

object LastMilePlanCarriersTable : LongIdTable("last_mile_plan_carriers") {
    val planId = varchar("plan_id", ID_LENGTH)
    val planRevision = long("plan_revision")
    val jobId = varchar("job_id", ID_LENGTH)
    val carrierVersion = long("carrier_version")

    init {
        uniqueIndex(planId, planRevision, jobId)
    }
}

object LastMilePlanStopsTable : LongIdTable("last_mile_plan_stops") {
    val planId = varchar("plan_id", ID_LENGTH)
    val planRevision = long("plan_revision")
    val vehicleId = varchar("vehicle_id", ID_LENGTH)
    val jobId = varchar("job_id", ID_LENGTH)
    val kind = varchar("kind", 16)
    val coordinateId = varchar("coordinate_id", ID_LENGTH)
    val sequence = integer("sequence")
    val eta = timestamp("eta")
    val loadAfter = integer("load_after")
    val pinned = bool("pinned")

    init {
        uniqueIndex(planId, planRevision, vehicleId, sequence)
    }
}

object LastMileUnassignedTable : LongIdTable("last_mile_unassigned") {
    val planId = varchar("plan_id", ID_LENGTH)
    val planRevision = long("plan_revision")
    val jobId = varchar("job_id", ID_LENGTH)
    val reason = varchar("reason", 32)

    init {
        uniqueIndex(planId, planRevision, jobId)
    }
}

object LastMileCommittedStopsTable : LongIdTable("last_mile_committed_stops") {
    val jobId = varchar("job_id", ID_LENGTH)
    val planId = varchar("plan_id", ID_LENGTH)
    val planRevision = long("plan_revision")
    val vehicleId = varchar("vehicle_id", ID_LENGTH)
    val kind = varchar("kind", 16)
    val sequence = integer("sequence")
    val carrierVersion = long("carrier_version")
    val committedAt = timestamp("committed_at")

    init {
        uniqueIndex(planId, planRevision, vehicleId, sequence)
    }
}

object LastMileEventsTable : AuditableLongIdTable("last_mile_events") {
    val eventId = varchar("event_id", ID_LENGTH).uniqueIndex()
    val aggregateId = varchar("aggregate_id", ID_LENGTH)
    val eventKey = varchar("event_key", 96)
    val eventType = varchar("event_type", 32)
    val occurredAt = timestamp("occurred_at")
    val canonicalPayload = varchar("canonical_payload", PAYLOAD_LENGTH)
    val digest = varchar("digest", 64)

    init {
        uniqueIndex(aggregateId, eventKey)
    }
}

object LastMileCallbackInboxTable : LongIdTable("last_mile_callback_inbox") {
    val provider = varchar("provider", 32)
    val eventId = varchar("event_id", ID_LENGTH)
    val requestId = varchar("request_id", 96)
    val providerRevision = long("provider_revision")
    val payloadDigest = varchar("payload_digest", 64)
    val status = varchar("status", 32)
    val receivedAt = timestamp("received_at")

    init {
        uniqueIndex(provider, eventId)
    }
}

object LastMileOutboxTable : AuditableLongIdTable("last_mile_outbox") {
    val eventType = varchar("event_type", 32)
    val payload = varchar("payload", PAYLOAD_LENGTH)
    val status = varchar("status", 16)
    val attempts = integer("attempts")
    val nextAttemptAt = timestamp("next_attempt_at")
    val leaseOwner = varchar("lease_owner", 96).nullable()
    val leaseUntil = timestamp("lease_until").nullable()
}

object LastMileAuditsTable : AuditableLongIdTable("last_mile_audits") {
    val planId = varchar("plan_id", ID_LENGTH).nullable()
    val planRevision = long("plan_revision").nullable()
    val decision = varchar("decision", 48)
    val redactedSummary = varchar("redacted_summary", 512)
}

object LastMileTables {
    val all: Array<org.jetbrains.exposed.v1.core.Table> = arrayOf(
        LastMileJobsTable,
        LastMileVehiclesTable,
        LastMileMatrixRevisionsTable,
        LastMileMatrixEdgesTable,
        LastMilePlansTable,
        LastMilePlanCarriersTable,
        LastMilePlanStopsTable,
        LastMileUnassignedTable,
        LastMileCommittedStopsTable,
        LastMileEventsTable,
        LastMileCallbackInboxTable,
        LastMileOutboxTable,
        LastMileAuditsTable,
    )

    init {
        require(all.size <= LastMileLimits.MAX_MATRIX_EDGES) { "table list sanity check failed" }
    }
}
