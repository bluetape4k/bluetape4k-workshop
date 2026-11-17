package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.operations.jobconsole.api.EtaConfidence
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.QueueProjection
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.domain.JobLease
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import io.bluetape4k.workshop.operations.jobconsole.domain.JobTransitions
import io.bluetape4k.workshop.operations.jobconsole.queue.QueueProjectionService
import io.bluetape4k.workshop.operations.jobconsole.queue.QueueRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.*
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

internal data class InsertedSubmittedJob(
    val jobId: UUID,
    val enqueueSequence: Long,
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
): RuntimeException(code.name)

class JobRepository(
    internal val dataSource: DataSource,
) : AutoCloseable {

    private val jdbc = JobJdbcExecutor(dataSource)

    /** Exposed database manager를 애플리케이션 lifecycle과 함께 해제합니다. */
    override fun close() {
        jdbc.close()
    }

    companion object: KLogging() {
        const val MAX_DURATION_SAMPLES: Int = 100
    }

    fun runnableTenantIds(limit: Int): List<String> {
        limit.requireInRange(1, 100, "limit")

        return jdbc.exposedTransaction {
            val oldestUpdatedAt = JobTable.updatedAt.min()
            JobTable
                .select(JobTable.tenantId, oldestUpdatedAt)
                .where {
                    (JobTable.state eq JobState.QUEUED.wireValue) or
                        ((JobTable.state inList listOf(JobState.RUNNING.wireValue, JobState.CANCEL_REQUESTED.wireValue)) and
                            (JobTable.leaseExpiresAt lessEq CurrentTimestamp))
                }
                .groupBy(JobTable.tenantId)
                .orderBy(
                    oldestUpdatedAt to SortOrder.ASC,
                    JobTable.tenantId to SortOrder.ASC,
                )
                .limit(limit)
                .map { it[JobTable.tenantId] }
        }
    }

    /** 애플리케이션 테이블에 의존하지 않는 connection 생존성 확인입니다. */
    fun databaseReady(): Boolean = runCatching {
        jdbc.exposedTransaction { select(CurrentTimestamp).singleOrNull() != null }
    }.getOrDefault(false)

    fun submit(
        scope: DemoCallerScope,
        idempotencyKey: String,
        request: SubmitJobRequest,
        now: Instant,
    ): SubmitJobResult {
        idempotencyKey.requireNotBlank("idempotencyKey")
        request.workUnits.requireInRange(1, 10_000, "request.workUnits")

        val keyHash = sha256("${scope.tenantId}:${scope.submitterHash}:$idempotencyKey")
        val fingerprint = sha256("${request.jobType.wireValue}:${request.workUnits}:${request.failureMode.wireValue}")

        return jdbc.transaction { connection ->
            advisoryLock(connection, "request:$keyHash")
            val existing =
                JobRequestTable
                    .select(JobRequestTable.requestFingerprint, JobRequestTable.jobId)
                    .where {
                        (JobRequestTable.tenantId eq scope.tenantId) and
                            (JobRequestTable.submitterHash eq normalizedSubmitterHash(scope.submitterHash)) and
                            (JobRequestTable.keyHash eq keyHash)
                    }
                    .singleOrNull()
                    ?.let {
                        ExistingRequest(
                            fingerprint = it[JobRequestTable.requestFingerprint].trim(),
                            jobId = it[JobRequestTable.jobId],
                        )
                    }
            existing?.let { existing ->
                if (existing.fingerprint != fingerprint) {
                    throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_KEY_REUSED)
                }
                return@transaction SubmitJobResult(
                    existing.jobId,
                    enqueueSequenceDsl(existing.jobId),
                    replayed = true,
                )
            }

            val jobId = Uuid.V7.nextId()
            // MAX(enqueue_sequence)+1은 tenant별 queue lock 안에서만 읽고 삽입해야 합니다.
            // legacy 경로도 bounded idempotency 경로와 같은 PostgreSQL 직렬화 경계를 사용합니다.
            advisoryLock(connection, "queue:${scope.tenantId}")
            val sequence = nextEnqueueSequenceDsl(scope.tenantId)
            insertJobDsl(scope, request, jobId, sequence, now)
            insertRequestDsl(scope, keyHash, fingerprint, jobId, now)
            insertOutboxDsl(jobId, now)
            insertHistoryDsl(jobId, now)
            SubmitJobResult(jobId, sequence, replayed = false)
        }
    }

    internal fun insertSubmittedJob(
        connection: Connection,
        scope: DemoCallerScope,
        request: SubmitJobRequest,
        jobId: UUID,
        now: Instant,
    ): InsertedSubmittedJob {
        advisoryLock(connection, "queue:${scope.tenantId}")
        val sequence = nextEnqueueSequenceDsl(scope.tenantId)
        insertJobDsl(scope, request, jobId, sequence, now)
        insertOutboxDsl(jobId, now)
        insertHistoryDsl(jobId, now)
        return InsertedSubmittedJob(jobId, sequence)
    }

    private fun nextEnqueueSequenceDsl(tenantId: String): Long {
        val maximum = JobTable.enqueueSequence.max()
        return (JobTable
            .select(maximum)
            .where { JobTable.tenantId eq tenantId }
            .singleOrNull()
            ?.get(maximum) ?: 0L) + 1L
    }

    private fun enqueueSequenceDsl(jobId: UUID): Long =
        JobTable
            .select(JobTable.enqueueSequence)
            .where { JobTable.jobId eq jobId }
            .single()[JobTable.enqueueSequence]

    private fun insertJobDsl(
        scope: DemoCallerScope,
        request: SubmitJobRequest,
        jobId: UUID,
        sequence: Long,
        now: Instant,
    ) {
        JobTable.insert { statement ->
            statement[JobTable.jobId] = jobId
            statement[JobTable.tenantId] = scope.tenantId
            statement[JobTable.submitterHash] = normalizedSubmitterHash(scope.submitterHash)
            statement[JobTable.jobType] = request.jobType.wireValue
            statement[JobTable.workUnits] = request.workUnits
            statement[JobTable.failureMode] = request.failureMode.wireValue
            statement[JobTable.enqueueSequence] = sequence
            statement[JobTable.state] = JobState.QUEUED.wireValue
            statement[JobTable.createdAt] = now
            statement[JobTable.updatedAt] = now
        }
    }

    private fun insertRequestDsl(
        scope: DemoCallerScope,
        keyHash: String,
        fingerprint: String,
        jobId: UUID,
        now: Instant,
    ) {
        JobRequestTable.insert { statement ->
            statement[JobRequestTable.tenantId] = scope.tenantId
            statement[JobRequestTable.submitterHash] = normalizedSubmitterHash(scope.submitterHash)
            statement[JobRequestTable.keyHash] = keyHash
            statement[JobRequestTable.requestFingerprint] = fingerprint
            statement[JobRequestTable.jobId] = jobId
            statement[JobRequestTable.createdAt] = now
            statement[JobRequestTable.state] = "TERMINAL"
            statement[JobRequestTable.generation] = 1L
            statement[JobRequestTable.updatedAt] = now
        }
    }

    private fun insertOutboxDsl(jobId: UUID, now: Instant) {
        listOf("job.updated", "queue.updated").forEach { eventType ->
            JobOutboxTable.insert { statement ->
                statement[JobOutboxTable.eventId] = Uuid.V7.nextId()
                statement[JobOutboxTable.jobId] = jobId
                statement[JobOutboxTable.eventType] = eventType
                statement[JobOutboxTable.resourceVersion] = 1L
                statement[JobOutboxTable.queueVersion] = 1L
                statement[JobOutboxTable.occurredAt] = now
            }
        }
    }

    private fun insertHistoryDsl(jobId: UUID, now: Instant) {
        JobHistoryTable.insert { statement ->
            statement[JobHistoryTable.historyId] = Uuid.V7.nextId()
            statement[JobHistoryTable.jobId] = jobId
            statement[JobHistoryTable.fromState] = null
            statement[JobHistoryTable.toState] = JobState.QUEUED.wireValue
            statement[JobHistoryTable.reasonCode] = "submitted"
            statement[JobHistoryTable.resourceVersion] = 1L
            statement[JobHistoryTable.occurredAt] = now
        }
    }

    /** PostgreSQL 제어 경로: advisory lock과 `FOR UPDATE SKIP LOCKED`로 lease 공정성을 보장합니다. */
    fun claimNext(tenantId: String, leaseDuration: Duration): ClaimedJob? = inTransaction { connection ->
        advisoryLock(connection, "claim:$tenantId")
        if (hasActiveLease(tenantId)) return@inTransaction null
        val candidate = connection.prepareStatement(
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

    /** PostgreSQL row lock과 database clock으로 만료 lease를 직렬화하는 제어 경로입니다. */
    fun reclaimExpired(tenantId: String, leaseDuration: Duration): ClaimedJob? =
        inTransaction { connection ->
            advisoryLock(connection, "claim:$tenantId")
            if (cancelExpiredLease(connection, tenantId)) return@inTransaction null

            val candidate = connection.prepareStatement(
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

    /** PostgreSQL 제어 경로: CAS update는 `RETURNING`과 interval 식을 하나의 원자 연산으로 사용합니다. */
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
            val nextState = if (state == JobState.CANCEL_REQUESTED) {
                JobTransitions.next(state, JobSignal.CHECKPOINT)
            } else {
                state
            }
            val updated = connection.prepareStatement(
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

    /** PostgreSQL 제어 경로: lease CAS와 `RETURNING`은 하나의 statement로 유지해야 합니다. */
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
            val progress =
                if (next == JobState.SUCCEEDED) {
                    100
                } else {
                    loadInCurrentTransaction(lease.jobId)?.progress ?: 0
                }
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
            requireNotNull(loadInCurrentTransaction(effectiveLease.jobId))
        }

    fun load(jobId: UUID): StoredJob? = jdbc.exposedTransaction {
        val job = JobTable
            .selectAll()
            .where { JobTable.jobId eq jobId }
            .singleOrNull()
            ?: return@exposedTransaction null
        val completedChunk = JobCheckpointTable
            .select(JobCheckpointTable.completedChunk)
            .where { JobCheckpointTable.jobId eq jobId }
            .singleOrNull()
            ?.get(JobCheckpointTable.completedChunk)
            ?: 0L
        job.toStoredJob(completedChunk)
    }

    fun load(scope: DemoCallerScope, jobId: UUID): StoredJob {
        val stored = load(jobId) ?: throw JobRepositoryException(JobProblemCode.JOB_NOT_FOUND)
        if (stored.tenantId != scope.tenantId || stored.submitterHash != normalizedSubmitterHash(scope.submitterHash)) {
            throw JobRepositoryException(JobProblemCode.SCOPE_DENIED)
        }
        return stored
    }

    fun loadTenantJobs(tenantId: String): List<StoredJob> = jdbc.exposedTransaction {
        // checkpoint를 job별로 다시 조회하지 않고 한 번의 LEFT JOIN으로 읽습니다.
        JobTable
            .leftJoin(JobCheckpointTable, { JobTable.jobId }, { JobCheckpointTable.jobId })
            .selectAll()
            .where { JobTable.tenantId eq tenantId }
            .orderBy(JobTable.enqueueSequence to SortOrder.ASC)
            .map { job -> job.toStoredJob(job.getOrNull(JobCheckpointTable.completedChunk) ?: 0L) }
    }

    /** PostgreSQL 제어 경로: row lock과 CAS `RETURNING`으로 cancellation 경쟁을 보호합니다. */
    fun cancel(scope: DemoCallerScope, jobId: UUID): CancelJobResult = inTransaction { connection ->
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
        val version = connection.prepareStatement(
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
        return jdbc.exposedTransaction {
            JobTable
                .select(
                    JobTable.jobId,
                    JobTable.jobType,
                    JobTable.state,
                    JobTable.enqueueSequence,
                    JobTable.queueVersion,
                    JobTable.updatedAt,
                )
                .where {
                    (JobTable.tenantId eq tenantId) and
                        (JobTable.state inList listOf(
                            JobState.QUEUED.wireValue,
                            JobState.RUNNING.wireValue,
                            JobState.CANCEL_REQUESTED.wireValue,
                        )) and
                        (afterSequence?.let { JobTable.enqueueSequence greater it } ?: Op.TRUE)
                }
                .orderBy(JobTable.enqueueSequence to SortOrder.ASC)
                .limit(boundedLimit)
                .map { it.toQueueRow() }
        }
    }

    fun queueProjection(tenantId: String, jobId: UUID, now: Instant): QueueProjection? =
        jdbc.exposedTransaction {
            val activeStates = listOf(
                JobState.QUEUED.wireValue,
                JobState.RUNNING.wireValue,
                JobState.CANCEL_REQUESTED.wireValue,
            )
            val target = JobTable
                .select(JobTable.enqueueSequence, JobTable.queueVersion)
                .where {
                    (JobTable.tenantId eq tenantId) and
                        (JobTable.jobId eq jobId) and
                        (JobTable.state inList activeStates)
                }
                .singleOrNull()
                ?: return@exposedTransaction null
            val targetSequence = target[JobTable.enqueueSequence]
            val jobsAhead = JobTable
                .select(JobTable.jobId)
                .where {
                    (JobTable.tenantId eq tenantId) and
                        (JobTable.enqueueSequence less targetSequence) and
                        (JobTable.state inList activeStates)
                }
                .count()
                .toInt()
            QueueProjection(
                position = jobsAhead + 1,
                jobsAhead = jobsAhead,
                estimatedStartRange = null,
                estimatedCompletionRange = null,
                confidence = EtaConfidence.INSUFFICIENT_DATA,
                sampleSize = 0,
                queueVersion = target[JobTable.queueVersion],
                updatedAt = now,
            )
        }

    fun submitterQueuePageRows(scope: DemoCallerScope, afterSequence: Long?, pageSize: Int): List<QueueRow> =
        jdbc.exposedTransaction {
            JobTable
                .select(
                    JobTable.jobId,
                    JobTable.jobType,
                    JobTable.state,
                    JobTable.enqueueSequence,
                    JobTable.queueVersion,
                    JobTable.updatedAt,
                )
                .where {
                    (JobTable.tenantId eq scope.tenantId) and
                        (JobTable.submitterHash eq normalizedSubmitterHash(scope.submitterHash)) and
                        (JobTable.state inList listOf(
                            JobState.QUEUED.wireValue,
                            JobState.RUNNING.wireValue,
                            JobState.CANCEL_REQUESTED.wireValue,
                        )) and
                        (afterSequence?.let { JobTable.enqueueSequence greater it } ?: Op.TRUE)
                }
                .orderBy(JobTable.enqueueSequence to SortOrder.ASC)
                .limit(pageSize.coerceIn(1, QueueProjectionService.MAX_PAGE_SIZE) + 1)
                .map { it.toQueueRow() }
        }

    fun recordDuration(jobType: JobType, duration: Duration, completedAt: Instant) {
        require(!duration.isNegative && !duration.isZero) { "duration must be positive" }
        jdbc.exposedTransaction {
            JobDurationSampleTable.insert { statement ->
                statement[JobDurationSampleTable.jobType] = jobType.wireValue
                statement[JobDurationSampleTable.durationMillis] = duration.toMillis()
                statement[JobDurationSampleTable.completedAt] = completedAt
            }
        }
    }

    fun durationSamples(jobType: JobType, since: Instant, limit: Int): List<Duration> {
        val boundedLimit = limit.coerceIn(1, MAX_DURATION_SAMPLES)
        return jdbc.exposedTransaction {
            JobDurationSampleTable
                .selectAll()
                .where {
                    (JobDurationSampleTable.jobType eq jobType.wireValue) and
                        (JobDurationSampleTable.completedAt greaterEq since)
                }
                .orderBy(
                    JobDurationSampleTable.completedAt to SortOrder.DESC,
                    JobDurationSampleTable.sampleId to SortOrder.DESC,
                )
                .limit(boundedLimit)
                .map { row -> Duration.ofMillis(row[JobDurationSampleTable.durationMillis]) }
        }
    }

    private fun hasActiveLease(tenantId: String): Boolean =
        JobTable
            .select(JobTable.jobId)
            .where {
                (JobTable.tenantId eq tenantId) and
                    (JobTable.state inList listOf(JobState.RUNNING.wireValue, JobState.CANCEL_REQUESTED.wireValue)) and
                    (JobTable.leaseExpiresAt greater CurrentTimestamp)
            }
            .limit(1)
            .any()

    /** 만료된 cancellation을 `FOR UPDATE`와 CAS `RETURNING`으로 한 번에 전이합니다. */
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
                    if (result.next()) result.getObject(
                        "job_id",
                        UUID::class.java
                    ) to result.getLong("version") else null
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

    /** database clock, interval, `RETURNING`이 필요한 PostgreSQL lease 제어 경로입니다. */
    private fun activateLease(
        connection: Connection,
        tenantId: String,
        candidate: ClaimCandidate,
        leaseDuration: Duration,
        expectedState: JobState?,
    ): ClaimedJob {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "lease duration must be positive" }
        val token = Uuid.V7.nextId()
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
        JobAttemptTable.insert {
            it[jobId] = candidate.jobId
            it[attempt] = lease.attempt
            it[leaseToken] = token
        }
        appendTransition(connection, candidate.jobId, expectedState, JobState.RUNNING, "claimed", lease.revision)
        return ClaimedJob(
            lease = lease,
            tenantId = tenantId,
            jobType = candidate.jobType,
            failureMode = candidate.failureMode,
            workUnits = candidate.workUnits,
            enqueueSequence = candidate.enqueueSequence,
            completedChunk = checkpointChunk(candidate.jobId),
        )
    }

    /** 유효한 lease를 `FOR UPDATE`로 잠그는 PostgreSQL 제어 경로입니다. */
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

    /** PostgreSQL `ON CONFLICT`와 database clock을 보존하는 checkpoint upsert입니다. */
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

    private fun checkpointChunk(jobId: UUID): Long =
        JobCheckpointTable
            .select(JobCheckpointTable.completedChunk)
            .where { JobCheckpointTable.jobId eq jobId }
            .singleOrNull()
            ?.get(JobCheckpointTable.completedChunk)
            ?: 0L

    /** PostgreSQL `ON CONFLICT`와 `INSERT ... SELECT ... CURRENT_TIMESTAMP`를 보존하는 raw 경로입니다. */
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
            statement.setObject(1, Uuid.V7.nextId())
            statement.setObject(2, jobId)
            statement.setString(3, from?.wireValue)
            statement.setString(4, to.wireValue)
            statement.setString(5, reason)
            statement.setLong(6, version)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO job_outbox(event_id, job_id, event_type, resource_version, queue_version, occurred_at)
            SELECT events.event_id, jobs.job_id, events.event_type, jobs.version, jobs.queue_version, CURRENT_TIMESTAMP
            FROM jobs
            CROSS JOIN (VALUES (?, 'job.updated'), (?, 'queue.updated')) AS events(event_id, event_type)
            WHERE jobs.job_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, Uuid.V7.nextId())
            statement.setObject(2, Uuid.V7.nextId())
            statement.setObject(3, jobId)
            statement.executeUpdate()
        }
    }

    /** 현재 Exposed transaction에서 job snapshot을 읽습니다. */
    internal fun loadInCurrentTransaction(jobId: UUID): StoredJob? {
        val job =
            JobTable
                .selectAll()
                .where { JobTable.jobId eq jobId }
                .singleOrNull()
                ?: return null
        val completedChunk =
            JobCheckpointTable
                .select(JobCheckpointTable.completedChunk)
                .where { JobCheckpointTable.jobId eq jobId }
                .singleOrNull()
                ?.get(JobCheckpointTable.completedChunk)
                ?: 0L
        return job.toStoredJob(completedChunk)
    }

    private fun ResultRow.toStoredJob(completedChunk: Long): StoredJob =
        StoredJob(
            jobId = this[JobTable.jobId],
            tenantId = this[JobTable.tenantId],
            submitterHash = this[JobTable.submitterHash].trim(),
            jobType = JobType.entries.single { it.wireValue == this[JobTable.jobType] },
            state = JobState.entries.single { it.wireValue == this[JobTable.state] },
            progress = this[JobTable.progress],
            completedChunk = completedChunk,
            enqueueSequence = this[JobTable.enqueueSequence],
            version = this[JobTable.version],
            updatedAt = this[JobTable.updatedAt],
        )

    private fun ResultRow.toQueueRow(): QueueRow =
        QueueRow(
            jobId = this[JobTable.jobId],
            jobType = JobType.entries.single { it.wireValue == this[JobTable.jobType] },
            state = JobState.entries.single { it.wireValue == this[JobTable.state] },
            enqueueSequence = this[JobTable.enqueueSequence],
            queueVersion = this[JobTable.queueVersion],
            updatedAt = this[JobTable.updatedAt],
        )

    private fun <T> inTransaction(block: (Connection) -> T): T = jdbc.transaction(block)

    /** tenant/request 단위 직렬화를 위한 PostgreSQL transaction advisory lock입니다. */
    private fun advisoryLock(connection: Connection, value: String) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { result -> result.requireNext() }
        }
    }

    private fun normalizedSubmitterHash(value: String): String =
        if (value.matches(Regex("[0-9a-f]{64}"))) value else sha256(value)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()).toHexString()

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
}
