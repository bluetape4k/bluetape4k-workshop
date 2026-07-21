package io.bluetape4k.workshop.leader.jobsafety.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

object JobAssignments : LongIdTable("job_safety_tenant_assignments") {
    val tenantId = varchar("tenant_id", 80).uniqueIndex()
    val membershipRevision = long("membership_revision")
    val regionId = varchar("region_id", 48)
    val regionEpoch = long("region_epoch")
    val active = bool("active")
}

object JobRolloutMarkers : LongIdTable("job_safety_rollout_markers") {
    val markerName = varchar("marker_name", 48).uniqueIndex()
    val namespaceEpoch = long("namespace_epoch")
    val minimumWriterVersion = integer("minimum_writer_version")
    val checkpointSchemaVersion = integer("checkpoint_schema_version")
}

object JobResources : LongIdTable("job_safety_resources") {
    val conflictKey = varchar("conflict_key", 200).uniqueIndex()
    val namespaceEpoch = long("namespace_epoch")
    val lastAcceptedFence = long("last_accepted_fence")
    val summaryValue = long("summary_value")
    val updatedAt = timestamp("updated_at")
}

object JobExecutions : LongIdTable("job_safety_executions") {
    val operationId = varchar("operation_id", 120).uniqueIndex()
    val jobName = varchar("job_name", 100)
    val tenantId = varchar("tenant_id", 80)
    val conflictKey = varchar("conflict_key", 200)
    val fencingOwnerId = varchar("fencing_owner_id", 160)
    val fencingToken = long("fencing_token")
    val state = varchar("state", 40)
    val rejection = varchar("rejection", 48).nullable()
    val contractVersion = integer("contract_version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object JobCheckpoints : LongIdTable("job_safety_checkpoints") {
    val conflictKey = varchar("conflict_key", 200).uniqueIndex()
    val fencingToken = long("fencing_token")
    val schemaVersion = integer("schema_version")
    val summaryValue = long("summary_value")
    val updatedAt = timestamp("updated_at")
}

object JobOutboxEntries : LongIdTable("job_safety_outbox") {
    val operationId = varchar("operation_id", 120).uniqueIndex()
    val effectType = varchar("effect_type", 48)
    val status = varchar("status", 32)
    val attemptCount = integer("attempt_count")
    val nextAttemptAt = timestamp("next_attempt_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object JobEffectReceipts : LongIdTable("job_safety_effect_receipts") {
    val provider = varchar("provider", 48)
    val operationId = varchar("operation_id", 120)
    val status = varchar("status", 32)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(provider, operationId)
    }
}

internal val JOB_SAFETY_TABLES: Array<Table> =
    arrayOf(
        JobAssignments,
        JobRolloutMarkers,
        JobResources,
        JobExecutions,
        JobCheckpoints,
        JobOutboxEntries,
        JobEffectReceipts,
    )
