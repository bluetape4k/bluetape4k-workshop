package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.domain.JobLease
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.domain.JobTransitions
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
    val state: JobState,
    val progress: Int,
    val completedChunk: Long,
    val enqueueSequence: Long,
    val version: Long,
)

class JobRepositoryException(
    val code: JobProblemCode,
) : RuntimeException(code.name)

class JobRepository(
    private val dataSource: DataSource,
) {

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
            val candidate =
                connection.prepareStatement(
                    """
                    SELECT job_id, job_type, failure_mode, work_units, enqueue_sequence, version
                    FROM jobs
                    WHERE tenant_id = ?
                      AND state IN (?, ?)
                      AND lease_expires_at <= CURRENT_TIMESTAMP
                    ORDER BY enqueue_sequence
                    FOR UPDATE
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, tenantId)
                    statement.setString(2, JobState.RUNNING.wireValue)
                    statement.setString(3, JobState.CANCEL_REQUESTED.wireValue)
                    statement.executeQuery().use { result -> if (result.next()) ClaimCandidate.from(result) else null }
                } ?: return@inTransaction null
            activateLease(connection, tenantId, candidate, leaseDuration, null)
        }

    fun checkpoint(lease: JobLease, completedChunk: Long, progress: Int): CheckpointResult =
        inTransaction { connection ->
            require(progress in 0..100) { "progress must be between 0 and 100" }
            val state = currentState(connection, lease)
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
                        lease_expires_at = CASE WHEN ? THEN NULL ELSE lease_expires_at END
                    WHERE job_id = ? AND lease_token = ? AND version = ?
                    RETURNING version, lease_expires_at
                    """.trimIndent(),
                ).use { statement ->
                    val terminal = nextState.terminal
                    statement.setString(1, nextState.wireValue)
                    statement.setInt(2, progress)
                    statement.setBoolean(3, terminal)
                    statement.setBoolean(4, terminal)
                    statement.setObject(5, lease.jobId)
                    statement.setObject(6, lease.token)
                    statement.setLong(7, lease.revision)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                        val expiresAt = result.getTimestamp("lease_expires_at")?.toInstant() ?: lease.expiresAt
                        lease.copy(revision = result.getLong("version"), expiresAt = expiresAt)
                    }
                }
            upsertCheckpoint(connection, lease.jobId, lease.token, completedChunk, progress)
            appendTransition(connection, lease.jobId, state, nextState, "checkpoint", updated.revision)
            CheckpointResult(updated, completedChunk, progress, nextState)
        }

    fun complete(lease: JobLease, signal: JobSignal): StoredJob =
        inTransaction { connection ->
            val current = currentState(connection, lease)
            val next = JobTransitions.next(current, signal)
            val progress = if (next == JobState.SUCCEEDED) 100 else load(lease.jobId, connection)?.progress ?: 0
            val version =
                connection.prepareStatement(
                    """
                    UPDATE jobs
                    SET state = ?, progress = ?, version = version + 1, queue_version = queue_version + 1,
                        lease_token = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE job_id = ? AND lease_token = ? AND version = ?
                    RETURNING version
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, next.wireValue)
                    statement.setInt(2, progress)
                    statement.setObject(3, lease.jobId)
                    statement.setObject(4, lease.token)
                    statement.setLong(5, lease.revision)
                    statement.executeQuery().use { result ->
                        if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                        result.getLong(1)
                    }
                }
            appendTransition(connection, lease.jobId, current, next, signal.name.lowercase(), version)
            requireNotNull(load(lease.jobId, connection))
        }

    fun load(jobId: UUID): StoredJob? = dataSource.connection.use { load(jobId, it) }

    fun loadTenantJobs(tenantId: String): List<StoredJob> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT j.job_id, j.tenant_id, j.state, j.progress, j.enqueue_sequence, j.version,
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

    private fun currentState(connection: Connection, lease: JobLease): JobState =
        connection.prepareStatement("SELECT state FROM jobs WHERE job_id = ? AND lease_token = ? AND version = ?").use { statement ->
            statement.setObject(1, lease.jobId)
            statement.setObject(2, lease.token)
            statement.setLong(3, lease.revision)
            statement.executeQuery().use { result ->
                if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                JobState.entries.single { it.wireValue == result.getString(1) }
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
            SELECT j.job_id, j.tenant_id, j.state, j.progress, j.enqueue_sequence, j.version,
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
            state = JobState.entries.single { it.wireValue == getString("state") },
            progress = getInt("progress"),
            completedChunk = getLong("completed_chunk"),
            enqueueSequence = getLong("enqueue_sequence"),
            version = getLong("version"),
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
}
