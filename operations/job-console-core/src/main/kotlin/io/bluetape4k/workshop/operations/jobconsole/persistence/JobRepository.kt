package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.EtaConfidence
import io.bluetape4k.workshop.operations.jobconsole.api.QueueProjection
import io.bluetape4k.workshop.operations.jobconsole.domain.JobLease
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.domain.JobTransitions
import io.bluetape4k.workshop.operations.jobconsole.queue.QueueProjectionService
import io.bluetape4k.workshop.operations.jobconsole.queue.QueueRow
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class DemoCallerScope(
    val tenantId: String,
    val submitterHash: String,
) {
    init {
        require(tenantId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid tenant scope" }
        require(submitterHash.isNotBlank()) { "submitter hash must not be blank" }
    }
}

data class SubmitJobResult(
    val jobId: UUID,
    val enqueueSequence: Long,
    val replayed: Boolean,
)

data class ClaimedJob(
    val lease: JobLease,
    val tenantId: String,
    val jobType: JobType,
    val failureMode: FailureMode,
    val workUnits: Int,
    val enqueueSequence: Long,
    val completedChunk: Long,
)

data class CheckpointResult(
    val lease: JobLease,
    val completedChunk: Long,
    val progress: Int,
    val state: JobState,
)

data class StoredJob(
    val jobId: UUID,
    val tenantId: String,
    val submitterHash: String,
    val jobType: JobType,
    val state: JobState,
    val progress: Int,
    val completedChunk: Long,
    val enqueueSequence: Long,
    val version: Long,
    val updatedAt: Instant,
)

data class CancelJobResult(
    val jobId: UUID,
    val state: JobState,
    val notificationRequired: Boolean,
)

class JobRepositoryException(
    val code: JobProblemCode,
) : RuntimeException(code.name)

class JobRepository(
    private val dataSource: DataSource,
) {
    fun runnableTenantIds(limit: Int): List<String> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT tenant_id, MIN(updated_at) AS oldest_updated_at
                FROM jobs
                WHERE state = ?
                   OR (state IN (?, ?) AND lease_expires_at <= CURRENT_TIMESTAMP)
                GROUP BY tenant_id
                ORDER BY oldest_updated_at, tenant_id
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, JobState.QUEUED.wireValue)
                statement.setString(2, JobState.RUNNING.wireValue)
                statement.setString(3, JobState.CANCEL_REQUESTED.wireValue)
                statement.setInt(4, limit)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getString("tenant_id")) } }
            }
        }
    }

    fun databaseReady(): Boolean =
        runCatching {
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT 1").use { statement ->
                    statement.executeQuery().use { result -> result.next() && result.getInt(1) == 1 }
                }
            }
        }.getOrDefault(false)

    fun submit(
        scope: DemoCallerScope,
        idempotencyKey: String,
        request: SubmitJobRequest,
        now: Instant,
    ): SubmitJobResult {
        require(idempotencyKey.isNotBlank()) { "Idempotency-Key must not be blank" }
        require(request.workUnits in 1..10_000) { "workUnits must be between 1 and 10000" }
        val keyHash = sha256("${scope.tenantId}:${scope.submitterHash}:$idempotencyKey")
        val fingerprint = sha256("${request.jobType.wireValue}:${request.workUnits}:${request.failureMode.wireValue}")

        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                advisoryLock(connection, "request:$keyHash")
                existingRequest(connection, scope, keyHash)?.let { existing ->
                    if (existing.fingerprint != fingerprint) {
                        throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_KEY_REUSED)
                    }
                    connection.commit()
                    return@use SubmitJobResult(existing.jobId, enqueueSequence(connection, existing.jobId), replayed = true)
                }

                advisoryLock(connection, "queue:${scope.tenantId}")
                val sequence = nextEnqueueSequence(connection, scope.tenantId)
                val jobId = UUID.randomUUID()
                insertJob(connection, scope, request, jobId, sequence, now)
                insertRequest(connection, scope, keyHash, fingerprint, jobId, now)
                insertOutbox(connection, jobId, now)
                insertHistory(connection, jobId, now)
                connection.commit()
                SubmitJobResult(jobId, sequence, replayed = false)
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            }
        }
    }

    fun claimNext(tenantId: String, leaseDuration: Duration): ClaimedJob? =
        inTransaction { connection ->
            advisoryLock(connection, "claim:$tenantId")
            if (hasActiveLease(connection, tenantId)) return@inTransaction null
            val candidate =
                connection.prepareStatement(
                    """
                    SELECT job_id, job_type, failure_mode, work_units, enqueue_sequence, version
                    FROM jobs
                    WHERE tenant_id = ? AND state = ?
                    ORDER BY enqueue_sequence
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, tenantId)
                    statement.setString(2, JobState.QUEUED.wireValue)
                    statement.executeQuery().use { result -> if (result.next()) ClaimCandidate.from(result) else null }
                } ?: return@inTransaction null
            activateLease(connection, tenantId, candidate, leaseDuration, JobState.QUEUED)
        }

    fun reclaimExpired(tenantId: String, leaseDuration: Duration): ClaimedJob? =
        inTransaction { connection ->
            advisoryLock(connection, "claim:$tenantId")
            if (cancelExpiredLease(connection, tenantId)) return@inTransaction null
            val candidate =
                connection.prepareStatement(
                    """
                    SELECT job_id, job_type, failure_mode, work_units, enqueue_sequence, version
                    FROM jobs
                    WHERE tenant_id = ?
                      AND state = ?
                      AND lease_expires_at <= CURRENT_TIMESTAMP
                    ORDER BY enqueue_sequence
                    FOR UPDATE
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, tenantId)
                    statement.setString(2, JobState.RUNNING.wireValue)
                    statement.executeQuery().use { result -> if (result.next()) ClaimCandidate.from(result) else null }
                } ?: return@inTransaction null
            activateLease(connection, tenantId, candidate, leaseDuration, null)
        }

    fun checkpoint(
        lease: JobLease,
        completedChunk: Long,
        progress: Int,
        renewalDuration: Duration = Duration.ofSeconds(30),
    ): CheckpointResult =
        inTransaction { connection ->
            require(progress in 0..100) { "progress must be between 0 and 100" }
            require(!renewalDuration.isNegative && !renewalDuration.isZero) { "renewal duration must be positive" }
            val leaseState = currentLeaseState(connection, lease)
            val state = leaseState.state
            val effectiveLease = lease.copy(revision = leaseState.version)
            val nextState =
                if (state == JobState.CANCEL_REQUESTED) {
                    JobTransitions.next(state, JobSignal.CHECKPOINT)
                } else {
                    state
                }
            val updated =
                connection.prepareStatement(
                    """
                    UPDATE jobs
                    SET state = ?, progress = ?, version = version + 1, queue_version = queue_version + 1,
                        updated_at = CURRENT_TIMESTAMP,
                        lease_token = CASE WHEN ? THEN NULL ELSE lease_token END,
                        lease_expires_at = CASE WHEN ? THEN NULL
                                                ELSE CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond') END
                    WHERE job_id = ? AND lease_token = ? AND version = ?
                      AND lease_expires_at > CURRENT_TIMESTAMP
                    RETURNING version, lease_expires_at
                    """.trimIndent(),
                ).use { statement ->
                    val terminal = nextState.terminal
                    statement.setString(1, nextState.wireValue)
                    statement.setInt(2, progress)
                    statement.setBoolean(3, terminal)
                    statement.setBoolean(4, terminal)
                    statement.setLong(5, renewalDuration.toMillis())
                    statement.setObject(6, effectiveLease.jobId)
                    statement.setObject(7, effectiveLease.token)
                    statement.setLong(8, effectiveLease.revision)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                        val expiresAt = result.getTimestamp("lease_expires_at")?.toInstant() ?: lease.expiresAt
                        effectiveLease.copy(revision = result.getLong("version"), expiresAt = expiresAt)
                    }
                }
            upsertCheckpoint(connection, lease.jobId, lease.token, completedChunk, progress)
            appendTransition(connection, lease.jobId, state, nextState, "checkpoint", updated.revision)
            CheckpointResult(updated, completedChunk, progress, nextState)
        }

    fun complete(lease: JobLease, signal: JobSignal): StoredJob =
        inTransaction { connection ->
            val leaseState = currentLeaseState(connection, lease)
            val effectiveLease = lease.copy(revision = leaseState.version)
            val current = leaseState.state
            val effectiveSignal =
                if (signal == JobSignal.RETRYABLE_FAILURE && leaseState.retryBudget == 0) {
                    JobSignal.RETRY_EXHAUSTED
                } else {
                    signal
                }
            val next = JobTransitions.next(current, effectiveSignal)
            val progress = if (next == JobState.SUCCEEDED) 100 else load(lease.jobId, connection)?.progress ?: 0
            val version =
                connection.prepareStatement(
                    """
                    UPDATE jobs
                    SET state = ?, progress = ?,
                        retry_budget = CASE WHEN ? THEN retry_budget - 1 ELSE retry_budget END,
                        version = version + 1, queue_version = queue_version + 1,
                        lease_token = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE job_id = ? AND lease_token = ? AND version = ?
                    RETURNING version
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, next.wireValue)
                    statement.setInt(2, progress)
                    statement.setBoolean(3, effectiveSignal == JobSignal.RETRYABLE_FAILURE)
                    statement.setObject(4, effectiveLease.jobId)
                    statement.setObject(5, effectiveLease.token)
                    statement.setLong(6, effectiveLease.revision)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                        result.getLong(1)
                    }
                }
            appendTransition(connection, effectiveLease.jobId, current, next, effectiveSignal.name.lowercase(), version)
            requireNotNull(load(effectiveLease.jobId, connection))
        }

    fun load(jobId: UUID): StoredJob? = dataSource.connection.use { load(jobId, it) }

    fun load(scope: DemoCallerScope, jobId: UUID): StoredJob {
        val stored = load(jobId) ?: throw JobRepositoryException(JobProblemCode.JOB_NOT_FOUND)
        if (stored.tenantId != scope.tenantId || stored.submitterHash != normalizedSubmitterHash(scope.submitterHash)) {
            throw JobRepositoryException(JobProblemCode.SCOPE_DENIED)
        }
        return stored
    }

    fun loadTenantJobs(tenantId: String): List<StoredJob> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT j.job_id, j.tenant_id, j.submitter_hash, j.job_type, j.state, j.progress,
                       j.enqueue_sequence, j.version, j.updated_at,
                       COALESCE(c.completed_chunk, 0) AS completed_chunk
                FROM jobs j
                LEFT JOIN job_checkpoints c ON c.job_id = j.job_id
                WHERE j.tenant_id = ?
                ORDER BY j.enqueue_sequence
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toStoredJob()) } }
            }
        }

    fun cancel(scope: DemoCallerScope, jobId: UUID): CancelJobResult =
        inTransaction { connection ->
            val row =
                connection.prepareStatement(
                    "SELECT tenant_id, submitter_hash, state, version FROM jobs WHERE job_id = ? FOR UPDATE",
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw JobRepositoryException(JobProblemCode.JOB_NOT_FOUND)
                        CancelRow(
                            tenantId = result.getString("tenant_id"),
                            submitterHash = result.getString("submitter_hash").trim(),
                            state = JobState.entries.single { it.wireValue == result.getString("state") },
                            version = result.getLong("version"),
                        )
                    }
                }
            if (row.tenantId != scope.tenantId || row.submitterHash != normalizedSubmitterHash(scope.submitterHash)) {
                throw JobRepositoryException(JobProblemCode.SCOPE_DENIED)
            }
            if (row.state.terminal || row.state == JobState.CANCEL_REQUESTED) {
                return@inTransaction CancelJobResult(jobId, row.state, row.state == JobState.CANCEL_REQUESTED)
            }

            val next = JobTransitions.next(row.state, JobSignal.CANCEL)
            val version =
                connection.prepareStatement(
                    """
                    UPDATE jobs
                    SET state = ?, version = version + 1, queue_version = queue_version + 1,
                        updated_at = CURRENT_TIMESTAMP,
                        lease_token = CASE WHEN ? THEN NULL ELSE lease_token END,
                        lease_expires_at = CASE WHEN ? THEN NULL ELSE lease_expires_at END
                    WHERE job_id = ? AND version = ?
                    RETURNING version
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, next.wireValue)
                    statement.setBoolean(2, next.terminal)
                    statement.setBoolean(3, next.terminal)
                    statement.setObject(4, jobId)
                    statement.setLong(5, row.version)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                        result.getLong("version")
                    }
                }
            appendTransition(connection, jobId, row.state, next, "cancel_requested", version)
            CancelJobResult(jobId, next, next == JobState.CANCEL_REQUESTED)
        }

    fun queueRows(tenantId: String, afterSequence: Long?, limit: Int): List<QueueRow> =
        queryQueueRows(tenantId, afterSequence, limit.coerceIn(1, QueueProjectionService.MAX_PAGE_SIZE))

    fun queuePageRows(tenantId: String, afterSequence: Long?, pageSize: Int): List<QueueRow> =
        queryQueueRows(
            tenantId,
            afterSequence,
            pageSize.coerceIn(1, QueueProjectionService.MAX_PAGE_SIZE) + 1,
        )

    private fun queryQueueRows(tenantId: String, afterSequence: Long?, boundedLimit: Int): List<QueueRow> {
        return dataSource.connection.use { connection ->
            val cursorClause = if (afterSequence == null) "" else "AND enqueue_sequence > ?"
            connection.prepareStatement(
                """
                SELECT job_id, job_type, state, enqueue_sequence, queue_version, updated_at
                FROM jobs
                WHERE tenant_id = ?
                  AND state IN (?, ?, ?)
                  $cursorClause
                ORDER BY enqueue_sequence
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.setString(2, JobState.QUEUED.wireValue)
                statement.setString(3, JobState.RUNNING.wireValue)
                statement.setString(4, JobState.CANCEL_REQUESTED.wireValue)
                if (afterSequence == null) {
                    statement.setInt(5, boundedLimit)
                } else {
                    statement.setLong(5, afterSequence)
                    statement.setInt(6, boundedLimit)
                }
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                QueueRow(
                                    jobId = result.getObject("job_id", UUID::class.java),
                                    jobType = JobType.entries.single { it.wireValue == result.getString("job_type") },
                                    state = JobState.entries.single { it.wireValue == result.getString("state") },
                                    enqueueSequence = result.getLong("enqueue_sequence"),
                                    queueVersion = result.getLong("queue_version"),
                                    updatedAt = result.getTimestamp("updated_at").toInstant(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun queueProjection(tenantId: String, jobId: UUID, now: Instant): QueueProjection? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT target.queue_version,
                       COUNT(earlier.job_id) AS jobs_ahead
                FROM jobs target
                LEFT JOIN jobs earlier
                  ON earlier.tenant_id = target.tenant_id
                 AND earlier.enqueue_sequence < target.enqueue_sequence
                 AND earlier.state IN (?, ?, ?)
                WHERE target.tenant_id = ?
                  AND target.job_id = ?
                  AND target.state IN (?, ?, ?)
                GROUP BY target.queue_version
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, JobState.QUEUED.wireValue)
                statement.setString(2, JobState.RUNNING.wireValue)
                statement.setString(3, JobState.CANCEL_REQUESTED.wireValue)
                statement.setString(4, tenantId)
                statement.setObject(5, jobId)
                statement.setString(6, JobState.QUEUED.wireValue)
                statement.setString(7, JobState.RUNNING.wireValue)
                statement.setString(8, JobState.CANCEL_REQUESTED.wireValue)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@use null
                    val jobsAhead = result.getInt("jobs_ahead")
                    QueueProjection(
                        position = jobsAhead + 1,
                        jobsAhead = jobsAhead,
                        estimatedStartRange = null,
                        estimatedCompletionRange = null,
                        confidence = EtaConfidence.INSUFFICIENT_DATA,
                        sampleSize = 0,
                        queueVersion = result.getLong("queue_version"),
                        updatedAt = now,
                    )
                }
            }
        }

    fun submitterQueuePageRows(scope: DemoCallerScope, afterSequence: Long?, pageSize: Int): List<QueueRow> =
        dataSource.connection.use { connection ->
            val cursorClause = if (afterSequence == null) "" else "AND enqueue_sequence > ?"
            connection.prepareStatement(
                """
                SELECT job_id, job_type, state, enqueue_sequence, queue_version, updated_at
                FROM jobs
                WHERE tenant_id = ? AND submitter_hash = ? AND state IN (?, ?, ?)
                $cursorClause
                ORDER BY enqueue_sequence
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, scope.tenantId)
                statement.setString(2, normalizedSubmitterHash(scope.submitterHash))
                statement.setString(3, JobState.QUEUED.wireValue)
                statement.setString(4, JobState.RUNNING.wireValue)
                statement.setString(5, JobState.CANCEL_REQUESTED.wireValue)
                if (afterSequence == null) {
                    statement.setInt(6, pageSize.coerceIn(1, QueueProjectionService.MAX_PAGE_SIZE) + 1)
                } else {
                    statement.setLong(6, afterSequence)
                    statement.setInt(7, pageSize.coerceIn(1, QueueProjectionService.MAX_PAGE_SIZE) + 1)
                }
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                QueueRow(
                                    jobId = result.getObject("job_id", UUID::class.java),
                                    jobType = JobType.entries.single { it.wireValue == result.getString("job_type") },
                                    state = JobState.entries.single { it.wireValue == result.getString("state") },
                                    enqueueSequence = result.getLong("enqueue_sequence"),
                                    queueVersion = result.getLong("queue_version"),
                                    updatedAt = result.getTimestamp("updated_at").toInstant(),
                                ),
                            )
                        }
                    }
                }
            }
        }

    fun recordDuration(jobType: JobType, duration: Duration, completedAt: Instant) {
        require(!duration.isNegative && !duration.isZero) { "duration must be positive" }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO job_duration_samples(job_type, duration_millis, completed_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, jobType.wireValue)
                statement.setLong(2, duration.toMillis())
                statement.setTimestamp(3, Timestamp.from(completedAt))
                statement.executeUpdate()
            }
        }
    }

    fun durationSamples(jobType: JobType, since: Instant, limit: Int): List<Duration> {
        val boundedLimit = limit.coerceIn(1, MAX_DURATION_SAMPLES)
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT duration_millis
                FROM job_duration_samples
                WHERE job_type = ? AND completed_at >= ?
                ORDER BY completed_at DESC, sample_id DESC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, jobType.wireValue)
                statement.setTimestamp(2, Timestamp.from(since))
                statement.setInt(3, boundedLimit)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(Duration.ofMillis(result.getLong("duration_millis"))) }
                }
            }
        }
    }

    private fun existingRequest(
        connection: Connection,
        scope: DemoCallerScope,
        keyHash: String,
    ): ExistingRequest? =
        connection.prepareStatement(
            """
            SELECT request_fingerprint, job_id
            FROM job_requests
            WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, scope.tenantId)
            statement.setString(2, normalizedSubmitterHash(scope.submitterHash))
            statement.setString(3, keyHash)
            statement.executeQuery().use { result ->
                if (result.next()) ExistingRequest(result.getString(1).trim(), result.getObject(2, UUID::class.java)) else null
            }
        }

    private fun nextEnqueueSequence(connection: Connection, tenantId: String): Long =
        connection.prepareStatement("SELECT COALESCE(MAX(enqueue_sequence), 0) + 1 FROM jobs WHERE tenant_id = ?").use { statement ->
            statement.setString(1, tenantId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }

    private fun enqueueSequence(connection: Connection, jobId: UUID): Long =
        connection.prepareStatement("SELECT enqueue_sequence FROM jobs WHERE job_id = ?").use { statement ->
            statement.setObject(1, jobId)
            statement.executeQuery().use { result -> result.requireNext(); result.getLong(1) }
        }

    private fun insertJob(
        connection: Connection,
        scope: DemoCallerScope,
        request: SubmitJobRequest,
        jobId: UUID,
        sequence: Long,
        now: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO jobs(
                job_id, tenant_id, submitter_hash, job_type, work_units, failure_mode,
                enqueue_sequence, state, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, jobId)
            statement.setString(2, scope.tenantId)
            statement.setString(3, normalizedSubmitterHash(scope.submitterHash))
            statement.setString(4, request.jobType.wireValue)
            statement.setInt(5, request.workUnits)
            statement.setString(6, request.failureMode.wireValue)
            statement.setLong(7, sequence)
            statement.setString(8, JobState.QUEUED.wireValue)
            statement.setTimestamp(9, Timestamp.from(now))
            statement.setTimestamp(10, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private fun insertRequest(
        connection: Connection,
        scope: DemoCallerScope,
        keyHash: String,
        fingerprint: String,
        jobId: UUID,
        now: Instant,
    ) {
        connection.prepareStatement(
            "INSERT INTO job_requests(tenant_id, submitter_hash, key_hash, request_fingerprint, job_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, scope.tenantId)
            statement.setString(2, normalizedSubmitterHash(scope.submitterHash))
            statement.setString(3, keyHash)
            statement.setString(4, fingerprint)
            statement.setObject(5, jobId)
            statement.setTimestamp(6, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private fun insertOutbox(connection: Connection, jobId: UUID, now: Instant) {
        connection.prepareStatement(
            "INSERT INTO job_outbox(event_id, job_id, event_type, resource_version, queue_version, occurred_at) VALUES (?, ?, ?, 1, 1, ?)",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, jobId)
            statement.setString(3, "job.updated")
            statement.setTimestamp(4, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private fun insertHistory(connection: Connection, jobId: UUID, now: Instant) {
        connection.prepareStatement(
            "INSERT INTO job_history(history_id, job_id, from_state, to_state, reason_code, resource_version, occurred_at) VALUES (?, ?, NULL, ?, ?, 1, ?)",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, jobId)
            statement.setString(3, JobState.QUEUED.wireValue)
            statement.setString(4, "submitted")
            statement.setTimestamp(5, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private fun hasActiveLease(connection: Connection, tenantId: String): Boolean =
        connection.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM jobs WHERE tenant_id = ? AND state IN (?, ?) AND lease_expires_at > CURRENT_TIMESTAMP)",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, JobState.RUNNING.wireValue)
            statement.setString(3, JobState.CANCEL_REQUESTED.wireValue)
            statement.executeQuery().use { result -> result.requireNext(); result.getBoolean(1) }
        }

    private fun cancelExpiredLease(connection: Connection, tenantId: String): Boolean {
        val candidate =
            connection.prepareStatement(
                """
                SELECT job_id, version
                FROM jobs
                WHERE tenant_id = ? AND state = ? AND lease_expires_at <= CURRENT_TIMESTAMP
                ORDER BY enqueue_sequence
                FOR UPDATE
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.setString(2, JobState.CANCEL_REQUESTED.wireValue)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getObject("job_id", UUID::class.java) to result.getLong("version") else null
                }
            } ?: return false
        val nextVersion =
            connection.prepareStatement(
                """
                UPDATE jobs
                SET state = ?, lease_token = NULL, lease_expires_at = NULL,
                    version = version + 1, queue_version = queue_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE job_id = ? AND version = ? AND state = ?
                RETURNING version
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, JobState.CANCELLED.wireValue)
                statement.setObject(2, candidate.first)
                statement.setLong(3, candidate.second)
                statement.setString(4, JobState.CANCEL_REQUESTED.wireValue)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                    result.getLong("version")
                }
            }
        appendTransition(
            connection,
            candidate.first,
            JobState.CANCEL_REQUESTED,
            JobState.CANCELLED,
            "expired_cancel_request",
            nextVersion,
        )
        return true
    }

    private fun activateLease(
        connection: Connection,
        tenantId: String,
        candidate: ClaimCandidate,
        leaseDuration: Duration,
        expectedState: JobState?,
    ): ClaimedJob {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "lease duration must be positive" }
        val token = UUID.randomUUID()
        val sql =
            buildString {
                append(
                    """
                    UPDATE jobs
                    SET state = ?, lease_token = ?,
                        lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                        attempt = attempt + 1, version = version + 1, queue_version = queue_version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE job_id = ? AND version = ?
                    """.trimIndent(),
                )
                if (expectedState != null) append(" AND state = ?")
                append(" RETURNING lease_expires_at, attempt, version")
            }
        val lease =
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, JobState.RUNNING.wireValue)
                statement.setObject(2, token)
                statement.setLong(3, leaseDuration.toMillis())
                statement.setObject(4, candidate.jobId)
                statement.setLong(5, candidate.version)
                if (expectedState != null) statement.setString(6, expectedState.wireValue)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                    JobLease(
                        jobId = candidate.jobId,
                        token = token,
                        attempt = result.getInt("attempt"),
                        expiresAt = result.getTimestamp("lease_expires_at").toInstant(),
                        revision = result.getLong("version"),
                    )
                }
            }
        connection.prepareStatement(
            "INSERT INTO job_attempts(job_id, attempt, lease_token) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, candidate.jobId)
            statement.setInt(2, lease.attempt)
            statement.setObject(3, token)
            statement.executeUpdate()
        }
        appendTransition(connection, candidate.jobId, expectedState, JobState.RUNNING, "claimed", lease.revision)
        return ClaimedJob(
            lease = lease,
            tenantId = tenantId,
            jobType = candidate.jobType,
            failureMode = candidate.failureMode,
            workUnits = candidate.workUnits,
            enqueueSequence = candidate.enqueueSequence,
            completedChunk = checkpointChunk(connection, candidate.jobId),
        )
    }

    private fun currentLeaseState(connection: Connection, lease: JobLease): LeaseState =
        connection.prepareStatement(
            "SELECT state, version, retry_budget FROM jobs " +
                "WHERE job_id = ? AND lease_token = ? AND lease_expires_at > CURRENT_TIMESTAMP FOR UPDATE",
        ).use { statement ->
            statement.setObject(1, lease.jobId)
            statement.setObject(2, lease.token)
            statement.executeQuery().use { result ->
                if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                LeaseState(
                    state = JobState.entries.single { it.wireValue == result.getString("state") },
                    version = result.getLong("version"),
                    retryBudget = result.getInt("retry_budget"),
                )
            }
        }

    private fun upsertCheckpoint(connection: Connection, jobId: UUID, token: UUID, chunk: Long, progress: Int) {
        connection.prepareStatement(
            """
            INSERT INTO job_checkpoints(job_id, lease_token, completed_chunk, progress)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (job_id) DO UPDATE
            SET lease_token = EXCLUDED.lease_token, completed_chunk = EXCLUDED.completed_chunk,
                progress = EXCLUDED.progress, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, jobId)
            statement.setObject(2, token)
            statement.setLong(3, chunk)
            statement.setInt(4, progress)
            statement.executeUpdate()
        }
    }

    private fun checkpointChunk(connection: Connection, jobId: UUID): Long =
        connection.prepareStatement("SELECT COALESCE((SELECT completed_chunk FROM job_checkpoints WHERE job_id = ?), 0)").use { statement ->
            statement.setObject(1, jobId)
            statement.executeQuery().use { result -> result.requireNext(); result.getLong(1) }
        }

    private fun appendTransition(
        connection: Connection,
        jobId: UUID,
        from: JobState?,
        to: JobState,
        reason: String,
        version: Long,
    ) {
        connection.prepareStatement(
            "INSERT INTO job_history(history_id, job_id, from_state, to_state, reason_code, resource_version, occurred_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, jobId)
            statement.setString(3, from?.wireValue)
            statement.setString(4, to.wireValue)
            statement.setString(5, reason)
            statement.setLong(6, version)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO job_outbox(event_id, job_id, event_type, resource_version, queue_version, occurred_at) SELECT ?, job_id, 'job.updated', version, queue_version, CURRENT_TIMESTAMP FROM jobs WHERE job_id = ?",
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, jobId)
            statement.executeUpdate()
        }
    }

    private fun load(jobId: UUID, connection: Connection): StoredJob? =
        connection.prepareStatement(
            """
            SELECT j.job_id, j.tenant_id, j.submitter_hash, j.job_type, j.state, j.progress,
                   j.enqueue_sequence, j.version, j.updated_at,
                   COALESCE(c.completed_chunk, 0) AS completed_chunk
            FROM jobs j LEFT JOIN job_checkpoints c ON c.job_id = j.job_id
            WHERE j.job_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, jobId)
            statement.executeQuery().use { result -> if (result.next()) result.toStoredJob() else null }
        }

    private fun ResultSet.toStoredJob(): StoredJob =
        StoredJob(
            jobId = getObject("job_id", UUID::class.java),
            tenantId = getString("tenant_id"),
            submitterHash = getString("submitter_hash").trim(),
            jobType = JobType.entries.single { it.wireValue == getString("job_type") },
            state = JobState.entries.single { it.wireValue == getString("state") },
            progress = getInt("progress"),
            completedChunk = getLong("completed_chunk"),
            enqueueSequence = getLong("enqueue_sequence"),
            version = getLong("version"),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun <T> inTransaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            }
        }

    private fun advisoryLock(connection: Connection, value: String) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { result -> result.requireNext() }
        }
    }

    private fun normalizedSubmitterHash(value: String): String =
        if (value.matches(Regex("[0-9a-f]{64}"))) value else sha256(value)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHexString()

    private fun ResultSet.requireNext() {
        check(next()) { "expected one database row" }
    }

    private data class ExistingRequest(val fingerprint: String, val jobId: UUID)

    private data class LeaseState(val state: JobState, val version: Long, val retryBudget: Int)

    private data class CancelRow(
        val tenantId: String,
        val submitterHash: String,
        val state: JobState,
        val version: Long,
    )

    private data class ClaimCandidate(
        val jobId: UUID,
        val jobType: JobType,
        val failureMode: FailureMode,
        val workUnits: Int,
        val enqueueSequence: Long,
        val version: Long,
    ) {
        companion object {
            fun from(result: ResultSet): ClaimCandidate =
                ClaimCandidate(
                    jobId = result.getObject("job_id", UUID::class.java),
                    jobType = JobType.entries.single { it.wireValue == result.getString("job_type") },
                    failureMode = FailureMode.entries.single { it.wireValue == result.getString("failure_mode") },
                    workUnits = result.getInt("work_units"),
                    enqueueSequence = result.getLong("enqueue_sequence"),
                    version = result.getLong("version"),
                )
        }
    }

    companion object {
        const val MAX_DURATION_SAMPLES: Int = 100
    }
}
