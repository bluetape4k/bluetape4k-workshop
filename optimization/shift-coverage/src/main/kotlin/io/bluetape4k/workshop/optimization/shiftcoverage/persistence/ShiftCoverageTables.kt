package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.exposed.core.auditable.AuditableUUIDTable
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageLimits
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/** worker source-of-truth와 schedule revision입니다. */
object ShiftCoverageWorkersTable : Table("shift_coverage_workers") {
    val id = long("id").autoIncrement()
    val siteId = varchar("site_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val workerId = varchar("worker_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val payload = text("payload")
    val revision = long("revision")
    val scheduleRevision = long("schedule_revision")
    init { uniqueIndex(siteId, workerId) }
    override val primaryKey = PrimaryKey(id)
}

/** shift demand와 started/pin 상태입니다. */
object ShiftCoverageShiftsTable : Table("shift_coverage_shifts") {
    val id = long("id").autoIncrement()
    val siteId = varchar("site_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val shiftId = varchar("shift_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val startAt = timestamp("start_at")
    val payload = text("payload")
    val revision = long("revision")
    init { uniqueIndex(siteId, shiftId); index(false, siteId, startAt, shiftId) }
    override val primaryKey = PrimaryKey(id)
}

/** authoritative current assignment이며 stale proposal는 이 표를 직접 쓰지 않습니다. */
object ShiftCoverageAssignmentsTable : Table("shift_coverage_assignments") {
    val id = long("id").autoIncrement()
    val siteId = varchar("site_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val shiftId = varchar("shift_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val workerId = varchar("worker_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val assignmentId = varchar("assignment_id", ShiftCoverageLimits.MAX_STRING_LENGTH).uniqueIndex()
    val revision = long("revision")
    val pinned = bool("pinned")
    val started = bool("started")
    init { uniqueIndex(siteId, shiftId, workerId); index(false, shiftId, workerId) }
    override val primaryKey = PrimaryKey(id)
}

/** immutable plan metadata의 UUID aggregate입니다. */
object ShiftCoveragePlansTable : AuditableUUIDTable("shift_coverage_plans") {
    val siteId = varchar("site_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val planId = varchar("plan_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val revision = long("revision")
    val snapshotDigest = char("snapshot_digest", 64)
    val payload = text("payload")
    init { uniqueIndex(siteId, planId, revision) }
}

/** durable generation state의 UUID aggregate입니다. */
object ShiftCoverageGenerationsTable : AuditableUUIDTable("shift_coverage_generations") {
    val generationId = varchar("generation_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val planId = varchar("plan_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val state = varchar("state", 24)
    val requestedAt = timestamp("requested_at")
    val completedAt = timestamp("completed_at").nullable()
    init { uniqueIndex(generationId); index(false, planId, state) }
}

/** provider/event unique inbox입니다. raw callback body는 저장하지 않습니다. */
object ShiftCoverageInboxTable : Table("shift_coverage_inbox") {
    val id = long("id").autoIncrement()
    val provider = varchar("provider", 32)
    val eventId = varchar("event_id", ShiftCoverageLimits.MAX_KEY_BYTES)
    val digest = char("digest", 64)
    val status = varchar("status", 24)
    val attempt = integer("attempt")
    val nextAttemptAt = timestamp("next_attempt_at")
    val requestId = varchar("request_id", ShiftCoverageLimits.MAX_KEY_BYTES)
    init { uniqueIndex(provider, eventId); index(false, status, nextAttemptAt, id) }
    override val primaryKey = PrimaryKey(id)
}

/** method/route/demo-scope/principal/key namespace와 canonical fingerprint입니다. */
object ShiftCoverageIdempotencyTable : Table("shift_coverage_idempotency") {
    const val FINGERPRINT_LENGTH: Int = 64
    val id = long("id").autoIncrement()
    val method = varchar("method", 16)
    val route = varchar("route", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val demoScope = varchar("demo_scope", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val principal = varchar("principal", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val key = varchar("key", ShiftCoverageLimits.MAX_KEY_BYTES)
    val fingerprintSha256 = char("fingerprint_sha256", FINGERPRINT_LENGTH)
    val response = text("response").nullable()
    val status = varchar("status", 24)
    init { uniqueIndex(method, route, demoScope, principal, key); index(false, fingerprintSha256) }
    override val primaryKey = PrimaryKey(id)
}

/** fenced effect lifecycle입니다. */
object ShiftCoverageOutboxTable : Table("shift_coverage_outbox") {
    val id = long("id").autoIncrement()
    val effectKey = varchar("effect_key", ShiftCoverageLimits.OPAQUE_TOKEN_LENGTH).uniqueIndex()
    val requestId = varchar("request_id", ShiftCoverageLimits.MAX_KEY_BYTES)
    val status = varchar("status", 24)
    val attempt = integer("attempt")
    val nextAttemptAt = timestamp("next_attempt_at")
    val leaseOwner = varchar("lease_owner", ShiftCoverageLimits.MAX_STRING_LENGTH).nullable()
    val leaseToken = varchar("lease_token", ShiftCoverageLimits.OPAQUE_TOKEN_LENGTH).nullable()
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val lastError = varchar("last_error", ShiftCoverageLimits.MAX_STRING_LENGTH).nullable()
    val createdAt = timestamp("created_at")
    init { index(false, status, nextAttemptAt, id) }
    override val primaryKey = PrimaryKey(id)
}

/** append-only redacted audit의 UUID aggregate입니다. */
object ShiftCoverageAuditsTable : AuditableUUIDTable("shift_coverage_audits") {
    val aggregateType = varchar("aggregate_type", 64)
    val aggregateId = varchar("aggregate_id", ShiftCoverageLimits.MAX_STRING_LENGTH)
    val decision = varchar("decision", 64)
    val summary = varchar("summary", ShiftCoverageLimits.MAX_STRING_LENGTH)
}

object ShiftCoverageTables {
    val all: Array<Table> = arrayOf(
        ShiftCoverageWorkersTable,
        ShiftCoverageShiftsTable,
        ShiftCoverageAssignmentsTable,
        ShiftCoveragePlansTable,
        ShiftCoverageGenerationsTable,
        ShiftCoverageInboxTable,
        ShiftCoverageIdempotencyTable,
        ShiftCoverageOutboxTable,
        ShiftCoverageAuditsTable,
    )
}
