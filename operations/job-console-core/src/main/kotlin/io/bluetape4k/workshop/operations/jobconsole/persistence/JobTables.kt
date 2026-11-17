package io.bluetape4k.workshop.operations.jobconsole.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object JobRequestTable: Table("job_requests") {
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

object JobRequestWaiterTable: Table("job_request_waiters") {
    val tenantId = varchar("tenant_id", 64)
    val submitterHash = char("submitter_hash", 64)
    val keyHash = char("key_hash", 64)
    val generation = long("generation")
    val waiterToken = javaUUID("waiter_token")
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(tenantId, submitterHash, keyHash, generation, waiterToken)
}

object JobTable: Table("jobs") {
    val jobId = javaUUID("job_id")
    val tenantId = varchar("tenant_id", 64)
    val submitterHash = char("submitter_hash", 64)
    // 스키마에는 enum 이름이 아니라 `document_export`와 같은 wire value를 저장합니다.
    val jobType = varchar("job_type", 48)
    val workUnits = integer("work_units")
    val failureMode = varchar("failure_mode", 32)
    val enqueueSequence = long("enqueue_sequence")
    val state = varchar("state", 32)
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

/** 작업 lease가 소유하는 영속 checkpoint 상태입니다. */
object JobCheckpointTable: Table("job_checkpoints") {
    val jobId = javaUUID("job_id")
    val leaseToken = javaUUID("lease_token")
    val completedChunk = long("completed_chunk")
    val resumeCursor = varchar("resume_cursor", 256).nullable()
    val progress = integer("progress")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(jobId)
}

/** claim된 시도마다 한 행을 저장하며, migration/schema 소유권은 SQL 리소스에 있습니다. */
object JobAttemptTable: Table("job_attempts") {
    val jobId = javaUUID("job_id")
    val attempt = integer("attempt")
    val leaseToken = javaUUID("lease_token")
    val startedAt = timestamp("started_at")
    val endedAt = timestamp("ended_at").nullable()
    val failureClass = varchar("failure_class", 64).nullable()

    override val primaryKey = PrimaryKey(jobId, attempt)
}

/** 프레임워크와 무관한 outbox poller가 소비하는 알림 행입니다. */
object JobOutboxTable: Table("job_outbox") {
    val eventId = javaUUID("event_id")
    val jobId = javaUUID("job_id")
    val eventType = varchar("event_type", 48)
    val resourceVersion = long("resource_version")
    val queueVersion = long("queue_version")
    val occurredAt = timestamp("occurred_at")
    val publishedAt = timestamp("published_at").nullable()
    val claimToken = javaUUID("claim_token").nullable()
    val claimExpiresAt = timestamp("claim_expires_at").nullable()

    override val primaryKey = PrimaryKey(eventId)
}

/** 진단과 replay 테스트에 사용하는 append-only 상태 전이 이력입니다. */
object JobHistoryTable: Table("job_history") {
    val historyId = javaUUID("history_id")
    val jobId = javaUUID("job_id")
    val fromState = varchar("from_state", 32).nullable()
    val toState = varchar("to_state", 32)
    val reasonCode = varchar("reason_code", 64)
    val resourceVersion = long("resource_version")
    val occurredAt = timestamp("occurred_at")

    override val primaryKey = PrimaryKey(historyId)
}

/** ETA 추정에 사용하는 제한된 실행 시간 표본입니다. */
object JobDurationSampleTable: Table("job_duration_samples") {
    val sampleId = long("sample_id").autoIncrement()
    val jobType = varchar("job_type", 48)
    val durationMillis = long("duration_millis")
    val completedAt = timestamp("completed_at")

    override val primaryKey = PrimaryKey(sampleId)
}
