package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object JobRequestTable : Table("job_requests") {
    val tenantId = varchar("tenant_id", 64)
    val submitterHash = char("submitter_hash", 64)
    val keyHash = char("key_hash", 64)
    val requestFingerprint = char("request_fingerprint", 64)
    val jobId = javaUUID("job_id")
    val createdAt = timestamp("created_at")
    val state = varchar("state", 16)
    val generation = long("generation")
    val ownerToken = javaUUID("owner_token").nullable()
    val ownerLeaseExpiresAt = timestamp("owner_lease_expires_at").nullable()
    val responseStatus = integer("response_status").nullable()
    val responseBody = binary("response_body", 64 * 1024).nullable()
    val responseContentType = varchar("response_content_type", 128).nullable()
    val responseHeaders = text("response_headers").nullable()
    val terminalAt = timestamp("terminal_at").nullable()
    val retainedUntil = timestamp("retained_until").nullable()
    val abandonedUntil = timestamp("abandoned_until").nullable()
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(tenantId, submitterHash, keyHash)
}

object JobRequestWaiterTable : Table("job_request_waiters") {
    val tenantId = varchar("tenant_id", 64)
    val submitterHash = char("submitter_hash", 64)
    val keyHash = char("key_hash", 64)
    val generation = long("generation")
    val waiterToken = javaUUID("waiter_token")
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(tenantId, submitterHash, keyHash, generation, waiterToken)
}

object JobTable : Table("jobs") {
    val jobId = javaUUID("job_id")
    val tenantId = varchar("tenant_id", 64)
    val submitterHash = char("submitter_hash", 64)
    val jobType = enumerationByName<JobType>("job_type", 48)
    val workUnits = integer("work_units")
    val failureMode = enumerationByName<FailureMode>("failure_mode", 32)
    val enqueueSequence = long("enqueue_sequence")
    val state = enumerationByName<JobState>("state", 32)
    val queueVersion = long("queue_version")
    val leaseToken = javaUUID("lease_token").nullable()
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val progress = integer("progress")
    val retryBudget = integer("retry_budget")
    val attempt = integer("attempt")
    val version = long("version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(jobId)
}
