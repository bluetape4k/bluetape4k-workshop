package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot
import io.bluetape4k.workshop.operations.jobconsole.idempotency.AbandonReason
import io.bluetape4k.workshop.operations.jobconsole.idempotency.CleanupReport
import io.bluetape4k.workshop.operations.jobconsole.idempotency.InFlightOwnership
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionCommand
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionOwnership
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PollResult
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PreparedJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.ReplayableJobSubmission
import io.bluetape4k.workshop.operations.jobconsole.idempotency.Reservation
import io.bluetape4k.workshop.operations.jobconsole.idempotency.WaiterRegistration
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.sql.DataSource

internal interface JobSubmissionIdempotencyRepository {
    fun reserve(command: JobSubmissionCommand, now: Instant): Reservation

    fun registerWaiter(ownership: InFlightOwnership, now: Instant): WaiterRegistration

    fun removeWaiter(scope: DemoCallerScope, keyHash: String, generation: Long, waiterToken: UUID): Boolean

    fun poll(scope: DemoCallerScope, keyHash: String, generation: Long, now: Instant): PollResult

    fun finalizeOwner(
        ownership: JobSubmissionOwnership,
        prepared: PreparedJobSubmission,
        now: Instant,
    ): ReplayableJobSubmission

    fun abandon(ownership: JobSubmissionOwnership, reason: AbandonReason, now: Instant): Boolean

    fun cleanupExpired(now: Instant, batchSize: Int = 100): CleanupReport
}

/** JDBC implementation whose request row is the authority for every state transition. */
internal class JdbcJobSubmissionIdempotencyRepository(
    private val dataSource: DataSource,
    private val jobRepository: JobRepository,
    private val policy: JobSubmissionIdempotencyPolicy = JobSubmissionIdempotencyPolicy(),
) : JobSubmissionIdempotencyRepository {

    override fun reserve(command: JobSubmissionCommand, now: Instant): Reservation {
        var attempt = 0
        while (true) {
            try {
                return inTransaction { connection -> reserveInTransaction(connection, command) }
            } catch (failure: SQLException) {
                if (failure.sqlState != UNIQUE_VIOLATION_SQL_STATE || attempt++ >= MAX_RESERVE_RETRIES) throw failure
            }
        }
    }

    override fun registerWaiter(ownership: InFlightOwnership, now: Instant): WaiterRegistration =
        inTransaction { connection ->
            val request = loadRequest(connection, ownership.ownership.scope, ownership.ownership.keyHash, forUpdate = true)
                ?: return@inTransaction WaiterRegistration.Overflow
            if (request.state != RequestState.IN_FLIGHT || request.generation != ownership.ownership.generation) {
                return@inTransaction WaiterRegistration.Overflow
            }
            deleteExpiredWaiters(connection, request.scope, request.keyHash, request.generation)
            val active = activeWaiterCount(connection, request.scope, request.keyHash, request.generation)
            if (active >= policy.maxWaitersPerKey) return@inTransaction WaiterRegistration.Overflow

            val waiterToken = UUID.randomUUID()
            connection.prepareStatement(
                """
                INSERT INTO job_request_waiters(
                    tenant_id, submitter_hash, key_hash, generation, waiter_token, expires_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'))
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, request.scope.tenantId)
                statement.setString(2, normalizedSubmitterHash(request.scope.submitterHash))
                statement.setString(3, request.keyHash)
                statement.setLong(4, request.generation)
                statement.setObject(5, waiterToken)
                statement.setLong(6, policy.waiterTimeout.toMillis())
                statement.executeUpdate()
            }
            WaiterRegistration.Registered(waiterToken, request.generation)
        }

    override fun removeWaiter(scope: DemoCallerScope, keyHash: String, generation: Long, waiterToken: UUID): Boolean =
        inTransaction { connection ->
            connection.prepareStatement(
                """
                DELETE FROM job_request_waiters
                WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ?
                  AND generation = ? AND waiter_token = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, scope.tenantId)
                statement.setString(2, normalizedSubmitterHash(scope.submitterHash))
                statement.setString(3, keyHash)
                statement.setLong(4, generation)
                statement.setObject(5, waiterToken)
                statement.executeUpdate() == 1
            }
        }

    override fun poll(scope: DemoCallerScope, keyHash: String, generation: Long, now: Instant): PollResult =
        inTransaction { connection ->
            val request = loadRequest(connection, scope, keyHash, forUpdate = false) ?: return@inTransaction PollResult.StillInFlight
            if (request.generation != generation) return@inTransaction PollResult.StillInFlight
            when (request.state) {
                RequestState.TERMINAL -> PollResult.Terminal(request.snapshot(connection))
                RequestState.ABANDONED -> PollResult.Abandoned(request.generation)
                RequestState.IN_FLIGHT -> PollResult.StillInFlight
            }
        }

    override fun finalizeOwner(
        ownership: JobSubmissionOwnership,
        prepared: PreparedJobSubmission,
        now: Instant,
    ): ReplayableJobSubmission {
        val replayHeaders = validatePrepared(prepared)
        return inTransaction { connection ->
            val enqueueSequence =
                jobRepository.insertSubmittedJob(
                    connection = connection,
                    scope = ownership.scope,
                    request = prepared.request,
                    jobId = ownership.jobId,
                    now = now,
                ).enqueueSequence
            val updated =
                connection.prepareStatement(
                    """
                    UPDATE job_requests
                    SET state = 'TERMINAL', owner_token = NULL, owner_lease_expires_at = NULL,
                        response_status = ?, response_body = ?, response_content_type = ?, response_headers = CAST(? AS JSONB),
                        terminal_at = CURRENT_TIMESTAMP,
                        retained_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ?
                      AND state = 'IN_FLIGHT' AND generation = ? AND owner_token = ?
                      AND owner_lease_expires_at > CURRENT_TIMESTAMP
                    RETURNING job_id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, prepared.responseStatus)
                    statement.setBytes(2, prepared.responseBody)
                    statement.setString(3, prepared.responseContentType)
                    statement.setString(4, encodeHeaders(replayHeaders))
                    statement.setLong(5, policy.retention.toMillis())
                    statement.setString(6, ownership.scope.tenantId)
                    statement.setString(7, normalizedSubmitterHash(ownership.scope.submitterHash))
                    statement.setString(8, ownership.keyHash)
                    statement.setLong(9, ownership.generation)
                    statement.setObject(10, ownership.ownerToken)
                    statement.executeQuery().use { result -> if (result.next()) result.getObject("job_id", UUID::class.java) else null }
                }
            if (updated == null) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
            ReplayableJobSubmission(
                jobId = updated,
                enqueueSequence = enqueueSequence,
                responseStatus = prepared.responseStatus,
                responseBody = prepared.responseBody.copyOf(),
                responseContentType = prepared.responseContentType,
                responseHeaders = replayHeaders.mapValues { (_, values) -> values.toList() },
            )
        }
    }

    override fun abandon(ownership: JobSubmissionOwnership, reason: AbandonReason, now: Instant): Boolean =
        inTransaction { connection ->
            connection.prepareStatement(
                """
                UPDATE job_requests
                SET state = 'ABANDONED', owner_token = NULL, owner_lease_expires_at = NULL,
                    abandoned_until = CURRENT_TIMESTAMP + interval '1 minute', updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ?
                  AND state = 'IN_FLIGHT' AND generation = ? AND owner_token = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, ownership.scope.tenantId)
                statement.setString(2, normalizedSubmitterHash(ownership.scope.submitterHash))
                statement.setString(3, ownership.keyHash)
                statement.setLong(4, ownership.generation)
                statement.setObject(5, ownership.ownerToken)
                statement.executeUpdate() == 1
            }
        }

    override fun cleanupExpired(now: Instant, batchSize: Int): CleanupReport {
        require(batchSize > 0) { "batchSize must be positive" }
        return inTransaction { connection ->
            val waitersDeleted =
                connection.prepareStatement(
                    """
                    WITH parent_candidates AS MATERIALIZED (
                        SELECT request.tenant_id, request.submitter_hash, request.key_hash
                        FROM job_requests request
                        WHERE EXISTS (
                            SELECT 1
                            FROM job_request_waiters waiter
                            WHERE waiter.tenant_id = request.tenant_id
                              AND waiter.submitter_hash = request.submitter_hash
                              AND waiter.key_hash = request.key_hash
                              AND waiter.expires_at <= CURRENT_TIMESTAMP
                        )
                        ORDER BY request.updated_at
                        FOR UPDATE OF request SKIP LOCKED
                        LIMIT ?
                    ),
                    candidates AS (
                        SELECT waiter.ctid
                        FROM job_request_waiters waiter
                        JOIN parent_candidates request
                          ON request.tenant_id = waiter.tenant_id
                         AND request.submitter_hash = waiter.submitter_hash
                         AND request.key_hash = waiter.key_hash
                        WHERE waiter.expires_at <= CURRENT_TIMESTAMP
                        ORDER BY waiter.expires_at
                        LIMIT ?
                    )
                    DELETE FROM job_request_waiters waiter
                    USING candidates
                    WHERE waiter.ctid = candidates.ctid
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, batchSize)
                    statement.setInt(2, batchSize)
                    statement.executeUpdate()
                }
            val requestsDeleted =
                connection.prepareStatement(
                    """
                    WITH candidates AS (
                        SELECT candidate.ctid
                        FROM job_requests candidate
                        WHERE (
                            (candidate.state = 'TERMINAL' AND COALESCE(candidate.retained_until, candidate.created_at + INTERVAL '1 hour') <= CURRENT_TIMESTAMP)
                            OR (candidate.state = 'ABANDONED' AND candidate.abandoned_until <= CURRENT_TIMESTAMP)
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM job_request_waiters waiter
                            WHERE waiter.tenant_id = candidate.tenant_id
                              AND waiter.submitter_hash = candidate.submitter_hash
                              AND waiter.key_hash = candidate.key_hash
                        )
                        ORDER BY candidate.updated_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT ?
                    )
                    DELETE FROM job_requests request
                    USING candidates
                    WHERE request.ctid = candidates.ctid
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, batchSize)
                    statement.executeUpdate()
                }
            CleanupReport(waitersDeleted, requestsDeleted)
        }
    }

    private fun reserveInTransaction(connection: Connection, command: JobSubmissionCommand): Reservation {
        val request = loadRequest(connection, command.scope, command.keyHash, forUpdate = true)
        if (request == null) {
            val ownership = insertInFlightRequest(connection, command)
            return Reservation.Owner(ownership)
        }
        if (request.state == RequestState.TERMINAL && request.retentionExpired) {
            return Reservation.Owner(takeOver(connection, request, command, incrementGeneration = true))
        }
        if (request.requestFingerprint != command.requestFingerprint) return Reservation.Conflict
        return when (request.state) {
            RequestState.TERMINAL ->
                Reservation.Replay(request.snapshot(connection))
            RequestState.ABANDONED -> {
                deleteExpiredWaiters(connection, request.scope, request.keyHash, request.generation)
                if (activeWaiterCount(connection, request.scope, request.keyHash, request.generation) > 0) {
                    Reservation.Abandoned
                } else {
                    Reservation.Owner(takeOver(connection, request, command, incrementGeneration = true))
                }
            }
            RequestState.IN_FLIGHT -> {
                if (request.leaseExpired) {
                    Reservation.Owner(takeOver(connection, request, command, incrementGeneration = false))
                } else {
                    Reservation.Wait(request.ownership())
                }
            }
        }
    }

    private fun insertInFlightRequest(connection: Connection, command: JobSubmissionCommand): JobSubmissionOwnership {
        val jobId = Uuid.V7.nextId()
        val ownerToken = UUID.randomUUID()
        val row =
            connection.prepareStatement(
                """
                INSERT INTO job_requests(
                    tenant_id, submitter_hash, key_hash, request_fingerprint, job_id, created_at,
                    state, generation, owner_token, owner_lease_expires_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'IN_FLIGHT', 1, ?,
                          CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), CURRENT_TIMESTAMP)
                RETURNING generation, owner_lease_expires_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, command.scope.tenantId)
                statement.setString(2, normalizedSubmitterHash(command.scope.submitterHash))
                statement.setString(3, command.keyHash)
                statement.setString(4, command.requestFingerprint)
                statement.setObject(5, jobId)
                statement.setObject(6, ownerToken)
                statement.setLong(7, policy.ownerLease.toMillis())
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong("generation") to result.getTimestamp("owner_lease_expires_at").toInstant()
                }
            }
        return JobSubmissionOwnership(command.scope, command.keyHash, row.first, jobId, ownerToken, row.second)
    }

    private fun takeOver(
        connection: Connection,
        request: RequestRow,
        command: JobSubmissionCommand,
        incrementGeneration: Boolean,
    ): JobSubmissionOwnership {
        val jobId = Uuid.V7.nextId()
        val ownerToken = UUID.randomUUID()
        val sqlGeneration = if (incrementGeneration) "generation + 1" else "generation"
        val row =
            connection.prepareStatement(
                """
                UPDATE job_requests
                SET request_fingerprint = ?, state = 'IN_FLIGHT', generation = $sqlGeneration, job_id = ?,
                    owner_token = ?, owner_lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    abandoned_until = NULL, response_status = NULL, response_body = NULL,
                    response_content_type = NULL, response_headers = NULL, terminal_at = NULL,
                    retained_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ? AND generation = ?
                RETURNING generation, owner_lease_expires_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, command.requestFingerprint)
                statement.setObject(2, jobId)
                statement.setObject(3, ownerToken)
                statement.setLong(4, policy.ownerLease.toMillis())
                statement.setString(5, request.scope.tenantId)
                statement.setString(6, normalizedSubmitterHash(request.scope.submitterHash))
                statement.setString(7, request.keyHash)
                statement.setLong(8, request.generation)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw JobRepositoryException(JobProblemCode.LEASE_LOST)
                    result.getLong("generation") to result.getTimestamp("owner_lease_expires_at").toInstant()
                }
            }
        return JobSubmissionOwnership(command.scope, command.keyHash, row.first, jobId, ownerToken, row.second)
    }

    private fun loadRequest(
        connection: Connection,
        scope: DemoCallerScope,
        keyHash: String,
        forUpdate: Boolean,
    ): RequestRow? {
        return connection.prepareStatement(
            """
            SELECT job_requests.request_fingerprint, job_requests.job_id AS job_id, job_requests.state,
                   job_requests.generation, job_requests.owner_token, job_requests.owner_lease_expires_at,
                   response_status, response_body, response_content_type, response_headers,
                   abandoned_until,
                   owner_lease_expires_at <= CURRENT_TIMESTAMP AS lease_expired,
                   COALESCE(job_requests.retained_until, job_requests.created_at + INTERVAL '1 hour') <= CURRENT_TIMESTAMP AS retention_expired,
                   jobs.enqueue_sequence
            FROM job_requests
            LEFT JOIN jobs ON jobs.job_id = job_requests.job_id
            WHERE job_requests.tenant_id = ? AND job_requests.submitter_hash = ? AND job_requests.key_hash = ?
            ${if (forUpdate) " FOR UPDATE OF job_requests" else ""}
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, scope.tenantId)
            statement.setString(2, normalizedSubmitterHash(scope.submitterHash))
            statement.setString(3, keyHash)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                RequestRow(
                    scope = scope,
                    keyHash = keyHash,
                    requestFingerprint = result.getString("request_fingerprint").trim(),
                    jobId = result.getObject("job_id", UUID::class.java),
                    enqueueSequence = result.getObject("enqueue_sequence")?.let { (it as Number).toLong() },
                    state = RequestState.valueOf(result.getString("state")),
                    generation = result.getLong("generation"),
                    ownerToken = result.getObject("owner_token", UUID::class.java),
                    ownerLeaseExpiresAt = result.getTimestamp("owner_lease_expires_at")?.toInstant(),
                    responseStatus = result.getObject("response_status")?.let { (it as Number).toInt() },
                    responseBody = result.getBytes("response_body"),
                    responseContentType = result.getString("response_content_type"),
                    responseHeaders = result.getString("response_headers"),
                    abandonedUntil = result.getTimestamp("abandoned_until")?.toInstant(),
                    leaseExpired = result.getBoolean("lease_expired"),
                    retentionExpired = result.getBoolean("retention_expired"),
                )
            }
        }
    }

    private fun activeWaiterCount(connection: Connection, scope: DemoCallerScope, keyHash: String, generation: Long): Int =
        connection.prepareStatement(
            "SELECT count(*) FROM job_request_waiters WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ? AND generation = ? AND expires_at > CURRENT_TIMESTAMP",
        ).use { statement ->
            statement.setString(1, scope.tenantId)
            statement.setString(2, normalizedSubmitterHash(scope.submitterHash))
            statement.setString(3, keyHash)
            statement.setLong(4, generation)
            statement.executeQuery().use { result -> check(result.next()); result.getInt(1) }
        }

    private fun deleteExpiredWaiters(connection: Connection, scope: DemoCallerScope, keyHash: String, generation: Long) {
        connection.prepareStatement(
            "DELETE FROM job_request_waiters WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ? AND generation = ? AND expires_at <= CURRENT_TIMESTAMP",
        ).use { statement ->
            statement.setString(1, scope.tenantId)
            statement.setString(2, normalizedSubmitterHash(scope.submitterHash))
            statement.setString(3, keyHash)
            statement.setLong(4, generation)
            statement.executeUpdate()
        }
    }

    private fun validatePrepared(prepared: PreparedJobSubmission): Map<String, List<String>> {
        require(prepared.responseStatus in 100..599) { "responseStatus must be a valid HTTP status" }
        require(prepared.responseBody.size <= policy.maxReplayBytes) { "response body exceeds replay limit" }
        require(prepared.responseContentType in setOf("application/json", "application/problem+json")) {
            "response content type is not replayable"
        }
        require(prepared.responseHeaders.size <= policy.maxHeaderNames) {
            "response header count exceeds replay limit"
        }
        val names = HashSet<String>(prepared.responseHeaders.size)
        val canonicalHeaders = linkedMapOf<String, List<String>>()
        var aggregateBytes = 0
        prepared.responseHeaders.forEach { (name, values) ->
            val canonicalName = name.lowercase(Locale.ROOT)
            require(HEADER_NAME.matches(canonicalName)) {
                "response header name must be an HTTP token"
            }
            require(names.add(canonicalName)) { "response header names must be unique" }
            require(canonicalName !in FORBIDDEN_REPLAY_HEADERS) { "response header is not replayable" }
            require(!SENSITIVE_HEADER_PATTERN.containsMatchIn(canonicalName)) {
                "response header is not replayable"
            }
            require(canonicalName in REPLAY_HEADER_ALLOWLIST) {
                "response header is not replayable"
            }
            require(canonicalName.length <= MAX_HEADER_NAME_LENGTH) {
                "response header name exceeds replay limit"
            }
            require(values.isNotEmpty() && values.size <= policy.maxHeaderValues) {
                "response header value count exceeds replay limit"
            }
            aggregateBytes += name.toByteArray(UTF_8).size
            values.forEach { value ->
                val valueBytes = value.toByteArray(UTF_8)
                require(valueBytes.size <= policy.maxHeaderValueBytes) {
                    "response header value exceeds replay limit"
                }
                require(value.all { it.code in 0x20..0x7e }) {
                    "response header value contains a control character"
                }
                aggregateBytes += valueBytes.size
            }
            canonicalHeaders[canonicalName] = values.toList()
        }
        require(aggregateBytes <= policy.maxAggregateHeaderBytes) {
            "response headers exceed aggregate replay limit"
        }
        return canonicalHeaders.toSortedMap()
    }

    private fun RequestRow.ownership(): JobSubmissionOwnership =
        JobSubmissionOwnership(
            scope = scope,
            keyHash = keyHash,
            generation = generation,
            jobId = jobId,
            ownerToken = requireNotNull(ownerToken),
            leaseExpiresAt = requireNotNull(ownerLeaseExpiresAt),
        )

    private fun RequestRow.snapshot(connection: Connection): ReplayableJobSubmission {
        if (responseStatus != null && responseBody != null && responseContentType != null && enqueueSequence != null) {
            return ReplayableJobSubmission(
                jobId = jobId,
                enqueueSequence = enqueueSequence,
                responseStatus = responseStatus,
                responseBody = responseBody.copyOf(),
                responseContentType = responseContentType,
                responseHeaders = decodeHeaders(responseHeaders),
            )
        }
        if (state != RequestState.TERMINAL) {
            throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED)
        }
        return materializeLegacySnapshot(connection, this)
    }

    private fun materializeLegacySnapshot(connection: Connection, request: RequestRow): ReplayableJobSubmission {
        val stored = jobRepository.load(request.jobId, connection)
            ?: throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED)
        if (
            stored.tenantId != request.scope.tenantId ||
            stored.submitterHash != normalizedSubmitterHash(request.scope.submitterHash)
        ) {
            throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED)
        }
        val snapshot =
            JobSnapshot(
                jobId = stored.jobId,
                jobType = stored.jobType,
                state = stored.state,
                progress = stored.progress,
                checkpoint = stored.completedChunk.takeIf { it > 0 },
                queue = null,
                version = stored.version,
                updatedAt = stored.updatedAt,
            )
        val body = jsonMapper.writeValueAsString(snapshot).toByteArray(UTF_8)
        if (body.size > policy.maxReplayBytes) {
            throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED)
        }
        val updated =
            connection.prepareStatement(
                """
                UPDATE job_requests
                SET response_status = 202, response_body = ?, response_content_type = 'application/json',
                    response_headers = CAST('{}' AS JSONB), terminal_at = COALESCE(terminal_at, CURRENT_TIMESTAMP),
                    retained_until = COALESCE(retained_until, created_at + INTERVAL '1 hour'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND submitter_hash = ? AND key_hash = ?
                  AND state = 'TERMINAL' AND generation = ? AND response_status IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, body)
                statement.setString(2, request.scope.tenantId)
                statement.setString(3, normalizedSubmitterHash(request.scope.submitterHash))
                statement.setString(4, request.keyHash)
                statement.setLong(5, request.generation)
                statement.executeUpdate()
            }
        if (updated == 0) {
            val reloaded = loadRequest(connection, request.scope, request.keyHash, forUpdate = true)
            if (reloaded == null || reloaded.responseStatus == null || reloaded.responseBody == null) {
                throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED)
            }
            return reloaded.snapshot(connection)
        }
        return ReplayableJobSubmission(
            jobId = stored.jobId,
            enqueueSequence = stored.enqueueSequence,
            responseStatus = 202,
            responseBody = body,
            responseContentType = "application/json",
            responseHeaders = emptyMap(),
        )
    }

    private fun <T> inTransaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                applyStatementTimeout(connection)
                block(connection).also { connection.commit() }
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            }
        }

    private fun applyStatementTimeout(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("SET LOCAL statement_timeout = '${policy.statementTimeout.toMillis()}ms'")
        }
    }

    private fun encodeHeaders(headers: Map<String, List<String>>): String =
        if (headers.isEmpty()) "{}" else jsonMapper.writeValueAsString(headers.toSortedMap())

    private fun decodeHeaders(headers: String?): Map<String, List<String>> =
        if (headers.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching {
                val node = jsonMapper.readTree(headers)
                if (!node.isObject) throw IllegalArgumentException("response headers must be an object")
                buildMap<String, List<String>> {
                    node.properties().forEach { (name, values) ->
                        if (!values.isArray || values.any { !it.isString }) {
                            throw IllegalArgumentException("response header values must be string arrays")
                        }
                        val strings = buildList<String> {
                            for (value in values) add(value.stringValue())
                        }
                        put(name, strings)
                    }
                }
            }.getOrElse { throw JobRepositoryException(JobProblemCode.IDEMPOTENCY_SNAPSHOT_REJECTED) }
        }

    private data class RequestRow(
        val scope: DemoCallerScope,
        val keyHash: String,
        val requestFingerprint: String,
        val jobId: UUID,
        val enqueueSequence: Long?,
        val state: RequestState,
        val generation: Long,
        val ownerToken: UUID?,
        val ownerLeaseExpiresAt: Instant?,
        val responseStatus: Int?,
        val responseBody: ByteArray?,
        val responseContentType: String?,
        val responseHeaders: String?,
        val abandonedUntil: Instant?,
        val leaseExpired: Boolean,
        val retentionExpired: Boolean,
    )

    private enum class RequestState {
        IN_FLIGHT,
        TERMINAL,
        ABANDONED,
    }

    private companion object {
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        const val MAX_RESERVE_RETRIES = 2
        val jsonMapper by lazy(LazyThreadSafetyMode.PUBLICATION) { jacksonObjectMapper() }
        val HEADER_NAME = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+""")
        const val MAX_HEADER_NAME_LENGTH = 128
        val SENSITIVE_HEADER_PATTERN = Regex(".*(auth|credential|cookie|password|secret|token|api[-_]?key).*")
        val REPLAY_HEADER_ALLOWLIST: Set<String> = emptySet()
        val FORBIDDEN_REPLAY_HEADERS =
            setOf(
                "authorization",
                "cookie",
                "set-cookie",
                "proxy-authenticate",
                "proxy-authorization",
                "connection",
                "keep-alive",
                "transfer-encoding",
                "upgrade",
                "content-length",
                "content-type",
                "idempotency-replayed",
                "retry-after",
                "x-api-key",
            )

        fun normalizedSubmitterHash(value: String): String =
            if (value.matches(Regex("[0-9a-f]{64}"))) {
                value
            } else {
                MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)).toHexString()
            }
    }
}
