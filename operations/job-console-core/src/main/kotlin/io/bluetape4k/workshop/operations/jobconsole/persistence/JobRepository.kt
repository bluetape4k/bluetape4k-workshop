package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
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
}
