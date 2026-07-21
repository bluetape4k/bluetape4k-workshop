@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.worker

import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class WorkerKind {
    RESERVATION_EXPIRY,
    ALLOCATION_EXPIRY,
    CAMPAIGN_REVOKE,
    BATCH_REVOKE,
    BATCH_EXPIRY,
    RECONCILIATION,
    PURGE,
}

internal data class WorkerPolicy(
    val lease: Duration = Duration.ofSeconds(15),
    val runDeadline: Duration = Duration.ofSeconds(30),
    val maxAttempts: Int = 5,
    val maximumBackoff: Duration = Duration.ofSeconds(30),
) {
    init {
        require(!lease.isNegative && !lease.isZero)
        require(!runDeadline.isNegative && !runDeadline.isZero)
        require(maxAttempts > 0)
        require(!maximumBackoff.isNegative && !maximumBackoff.isZero)
    }
}

internal data class WorkerClaim(
    val tenantId: String,
    val kind: WorkerKind,
    val scopeId: UUID,
    val owner: String,
    val claimUntil: Instant,
    val cursor: Long,
    val attempt: Int,
    val nextAttemptAt: Instant,
    val checkpoint: Long,
    val revision: Long,
    val runDeadline: Instant,
)

internal data class WorkerClaimSnapshot(
    val tenantId: String,
    val kind: WorkerKind,
    val scopeId: UUID,
    val owner: String?,
    val claimUntil: Instant?,
    val cursor: Long,
    val attempt: Int,
    val nextAttemptAt: Instant,
    val checkpoint: Long,
    val poisonReason: String?,
    val revision: Long,
) {
    val state: WorkerClaimState
        get() = when {
            poisonReason != null -> WorkerClaimState.POISONED
            owner != null -> WorkerClaimState.OWNED
            attempt > 0 -> WorkerClaimState.RETRYABLE
            else -> WorkerClaimState.IDLE
        }

    val nextAction: String
        get() = when (state) {
            WorkerClaimState.POISONED -> "OPERATOR_REVIEW_REQUIRED"
            WorkerClaimState.RETRYABLE -> "RETRY_AFTER_BACKOFF"
            WorkerClaimState.OWNED -> "WAIT_FOR_CHECKPOINT"
            WorkerClaimState.IDLE -> "CLAIM_AVAILABLE"
        }
}

internal enum class WorkerClaimState {
    IDLE,
    OWNED,
    RETRYABLE,
    POISONED,
}

internal data class WorkerFailure(
    val snapshot: WorkerClaimSnapshot,
    val backoffSeconds: Long,
)

internal class StaleWorkerClaimException : IllegalStateException("worker claim owner or revision is stale")

internal interface VoucherPoolWorkerRepository {
    fun claim(tenantId: String, kind: WorkerKind, scopeId: UUID, owner: String): WorkerClaim?
    fun checkpoint(claim: WorkerClaim, cursor: Long): WorkerClaim
    fun fail(claim: WorkerClaim, reason: String): WorkerFailure
    fun release(claim: WorkerClaim): WorkerClaimSnapshot
    fun finalize(claim: WorkerClaim): WorkerClaimSnapshot
    fun snapshot(tenantId: String, kind: WorkerKind, scopeId: UUID): WorkerClaimSnapshot?
}

internal class JdbcVoucherPoolWorkerRepository(
    private val executor: VoucherPoolJdbcExecutor,
    private val policy: WorkerPolicy = WorkerPolicy(),
) : VoucherPoolWorkerRepository {

    override fun claim(tenantId: String, kind: WorkerKind, scopeId: UUID, owner: String): WorkerClaim? {
        require(tenantId.isNotBlank())
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH)
        return executor.workerTransaction {
            val connection = currentConnection()
            insertClaimIfMissing(connection, tenantId, kind, scopeId)
            val current = lockSnapshot(connection, tenantId, kind, scopeId) ?: return@workerTransaction null
            val now = transactionTime(connection)
            if (current.poisonReason != null) return@workerTransaction null
            if (current.nextAttemptAt > now) return@workerTransaction null
            if (current.owner != null && checkNotNull(current.claimUntil) > now) return@workerTransaction null
            connection.prepareStatement(
                """UPDATE voucher_pool_worker_claims SET owner_id=?,claim_until=?::timestamptz,revision=revision+1
                    WHERE tenant_id=? AND worker_type=? AND scope_id=? AND revision=?""",
            ).use { statement ->
                statement.setString(1, owner)
                statement.setTimestamp(2, Timestamp.from(now.plus(policy.lease)))
                statement.setString(3, tenantId)
                statement.setString(4, kind.name)
                statement.setObject(5, scopeId)
                statement.setLong(6, current.revision)
                check(statement.executeUpdate() == 1)
            }
            checkNotNull(lockSnapshot(connection, tenantId, kind, scopeId)).ownedClaim(now.plus(policy.runDeadline))
        }
    }

    override fun checkpoint(claim: WorkerClaim, cursor: Long): WorkerClaim {
        require(cursor >= 0L)
        return executor.workerTransaction { checkpointInTransaction(currentConnection(), claim, cursor) }
    }

    internal fun checkpointInTransaction(connection: Connection, claim: WorkerClaim, cursor: Long): WorkerClaim {
        require(cursor >= 0L)
        val current = requireCurrentOwner(connection, claim)
        val now = transactionTime(connection)
        if (claim.runDeadline <= now) throw StaleWorkerClaimException()
        connection.prepareStatement(
            """UPDATE voucher_pool_worker_claims SET cursor=?,checkpoint=checkpoint+1,attempt=0,
                  next_attempt_at=?,claim_until=?,revision=revision+1
                WHERE tenant_id=? AND worker_type=? AND scope_id=? AND revision=? AND owner_id=?""",
        ).use { statement ->
            statement.setLong(1, cursor)
            statement.setTimestamp(2, Timestamp.from(now))
            statement.setTimestamp(3, Timestamp.from(now.plus(policy.lease)))
            statement.setString(4, claim.tenantId)
            statement.setString(5, claim.kind.name)
            statement.setObject(6, claim.scopeId)
            statement.setLong(7, current.revision)
            statement.setString(8, claim.owner)
            check(statement.executeUpdate() == 1)
        }
        return checkNotNull(lockSnapshot(connection, claim.tenantId, claim.kind, claim.scopeId)).ownedClaim(claim.runDeadline)
    }

    internal fun requireCurrentInTransaction(connection: Connection, claim: WorkerClaim) {
        requireCurrentOwner(connection, claim)
    }

    override fun fail(claim: WorkerClaim, reason: String): WorkerFailure {
        require(reason.matches(BOUNDED_REASON))
        return executor.workerTransaction {
            val connection = currentConnection()
            val current = requireCurrentOwner(connection, claim)
            val now = transactionTime(connection)
            val nextAttempt = current.attempt + 1
            val backoffSeconds = boundedBackoff(nextAttempt)
            val poisonReason = reason.takeIf { nextAttempt >= policy.maxAttempts }
            connection.prepareStatement(
                """UPDATE voucher_pool_worker_claims SET owner_id=NULL,claim_until=NULL,attempt=?,next_attempt_at=?,
                      poison_reason=?,revision=revision+1
                    WHERE tenant_id=? AND worker_type=? AND scope_id=? AND revision=? AND owner_id=?""",
            ).use { statement ->
                statement.setInt(1, nextAttempt)
                statement.setTimestamp(2, Timestamp.from(now.plusSeconds(backoffSeconds)))
                statement.setString(3, poisonReason)
                statement.setString(4, claim.tenantId)
                statement.setString(5, claim.kind.name)
                statement.setObject(6, claim.scopeId)
                statement.setLong(7, current.revision)
                statement.setString(8, claim.owner)
                check(statement.executeUpdate() == 1)
            }
            WorkerFailure(
                checkNotNull(lockSnapshot(connection, claim.tenantId, claim.kind, claim.scopeId)),
                backoffSeconds,
            )
        }
    }

    override fun release(claim: WorkerClaim): WorkerClaimSnapshot = complete(claim, resetCursor = false)

    override fun finalize(claim: WorkerClaim): WorkerClaimSnapshot = complete(claim, resetCursor = true)

    override fun snapshot(tenantId: String, kind: WorkerKind, scopeId: UUID): WorkerClaimSnapshot? =
        executor.workerTransaction { selectSnapshot(currentConnection(), tenantId, kind, scopeId, forUpdate = false) }

    internal fun ensureClaimInTransaction(
        connection: Connection,
        tenantId: String,
        kind: WorkerKind,
        scopeId: UUID,
    ): Boolean = insertClaimIfMissing(connection, tenantId, kind, scopeId)

    internal fun scheduleReconciliationInTransaction(
        connection: Connection,
        tenantId: String,
        scopeId: UUID,
    ): Boolean {
        val inserted = insertClaimIfMissing(connection, tenantId, WorkerKind.RECONCILIATION, scopeId)
        if (inserted) return true
        val current = lockSnapshot(connection, tenantId, WorkerKind.RECONCILIATION, scopeId)
        val completed = current != null && current.owner == null && current.cursor == 0L && current.attempt == 0 &&
            current.poisonReason == null && current.checkpoint > 0L
        return completed && connection.prepareStatement(
            """UPDATE voucher_pool_worker_claims
                SET checkpoint=0,next_attempt_at=transaction_timestamp(),revision=revision+1
                WHERE tenant_id=? AND worker_type=? AND scope_id=? AND revision=?""",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, WorkerKind.RECONCILIATION.name)
            statement.setObject(3, scopeId)
            statement.setLong(4, checkNotNull(current).revision)
            statement.executeUpdate() == 1
        }
    }

    internal fun ensureBatchRevokeClaimsForCampaignInTransaction(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
        limit: Int,
    ): Int = connection.prepareStatement(
        """INSERT INTO voucher_pool_worker_claims(tenant_id,worker_type,scope_id)
            SELECT b.tenant_id,?,b.batch_id FROM voucher_pool_batches b
            WHERE b.tenant_id=? AND b.campaign_id=? AND b.state NOT IN ('REVOKED','EXPIRED')
              AND NOT EXISTS (SELECT 1 FROM voucher_pool_worker_claims w
                  WHERE w.tenant_id=b.tenant_id AND w.worker_type=? AND w.scope_id=b.batch_id)
            ORDER BY b.batch_id LIMIT ? ON CONFLICT DO NOTHING""",
    ).use { statement ->
        statement.setString(1, WorkerKind.BATCH_REVOKE.name)
        statement.setString(2, tenantId)
        statement.setObject(3, campaignId)
        statement.setString(4, WorkerKind.BATCH_REVOKE.name)
        statement.setInt(5, limit)
        statement.executeUpdate()
    }

    internal fun hasUnscheduledBatchRevokesInTransaction(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
    ): Boolean = connection.prepareStatement(
        """SELECT EXISTS (SELECT 1 FROM voucher_pool_batches b
            WHERE b.tenant_id=? AND b.campaign_id=? AND b.state NOT IN ('REVOKED','EXPIRED')
              AND NOT EXISTS (SELECT 1 FROM voucher_pool_worker_claims w
                  WHERE w.tenant_id=b.tenant_id AND w.worker_type=? AND w.scope_id=b.batch_id))""",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setObject(2, campaignId)
        statement.setString(3, WorkerKind.BATCH_REVOKE.name)
        statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
    }

    private fun complete(claim: WorkerClaim, resetCursor: Boolean): WorkerClaimSnapshot =
        executor.workerTransaction { completeInTransaction(currentConnection(), claim, resetCursor) }

    internal fun completeInTransaction(
        connection: Connection,
        claim: WorkerClaim,
        resetCursor: Boolean = true,
    ): WorkerClaimSnapshot {
        val current = requireCurrentOwner(connection, claim)
        val now = transactionTime(connection)
        connection.prepareStatement(
            """UPDATE voucher_pool_worker_claims SET owner_id=NULL,claim_until=NULL,cursor=?,next_attempt_at=?,revision=revision+1
                WHERE tenant_id=? AND worker_type=? AND scope_id=? AND revision=? AND owner_id=?""",
        ).use { statement ->
            statement.setLong(1, if (resetCursor) 0L else current.cursor)
            statement.setTimestamp(2, Timestamp.from(now))
            statement.setString(3, claim.tenantId)
            statement.setString(4, claim.kind.name)
            statement.setObject(5, claim.scopeId)
            statement.setLong(6, current.revision)
            statement.setString(7, claim.owner)
            check(statement.executeUpdate() == 1)
        }
        return checkNotNull(lockSnapshot(connection, claim.tenantId, claim.kind, claim.scopeId))
    }

    private fun requireCurrentOwner(connection: Connection, claim: WorkerClaim): WorkerClaimSnapshot {
        val current = lockSnapshot(connection, claim.tenantId, claim.kind, claim.scopeId)
            ?: throw StaleWorkerClaimException()
        val now = transactionTime(connection)
        val identityIsStale = current.owner != claim.owner || current.revision != claim.revision
        val leaseIsStale = current.claimUntil == null || current.claimUntil <= now
        if (identityIsStale || leaseIsStale) throw StaleWorkerClaimException()
        return current
    }

    private fun boundedBackoff(attempt: Int): Long {
        val exponential = 1L shl (attempt - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        return minOf(exponential, policy.maximumBackoff.seconds)
    }

    private fun insertClaimIfMissing(connection: Connection, tenantId: String, kind: WorkerKind, scopeId: UUID): Boolean =
        connection.prepareStatement(
            """INSERT INTO voucher_pool_worker_claims(tenant_id,worker_type,scope_id)
                VALUES (?,?,?) ON CONFLICT DO NOTHING""",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, kind.name)
            statement.setObject(3, scopeId)
            statement.executeUpdate() == 1
        }

    private fun lockSnapshot(connection: Connection, tenantId: String, kind: WorkerKind, scopeId: UUID): WorkerClaimSnapshot? =
        selectSnapshot(connection, tenantId, kind, scopeId, forUpdate = true)

    private fun selectSnapshot(
        connection: Connection,
        tenantId: String,
        kind: WorkerKind,
        scopeId: UUID,
        forUpdate: Boolean,
    ): WorkerClaimSnapshot? = connection.prepareStatement(
        "SELECT * FROM voucher_pool_worker_claims WHERE tenant_id=? AND worker_type=? AND scope_id=?" +
            if (forUpdate) " FOR UPDATE" else "",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setString(2, kind.name)
        statement.setObject(3, scopeId)
        statement.executeQuery().use { result -> if (result.next()) result.workerClaimSnapshot() else null }
    }

    private fun transactionTime(connection: Connection): Instant =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT transaction_timestamp()").use { result -> result.next(); result.getTimestamp(1).toInstant() }
        }

    private fun WorkerClaimSnapshot.ownedClaim(deadline: Instant): WorkerClaim = WorkerClaim(
        tenantId,
        kind,
        scopeId,
        checkNotNull(owner),
        checkNotNull(claimUntil),
        cursor,
        attempt,
        nextAttemptAt,
        checkpoint,
        revision,
        deadline,
    )

    private fun ResultSet.workerClaimSnapshot() = WorkerClaimSnapshot(
        tenantId = getString("tenant_id"),
        kind = WorkerKind.valueOf(getString("worker_type")),
        scopeId = getObject("scope_id", UUID::class.java),
        owner = getString("owner_id"),
        claimUntil = getTimestamp("claim_until")?.toInstant(),
        cursor = getLong("cursor"),
        attempt = getInt("attempt"),
        nextAttemptAt = getTimestamp("next_attempt_at").toInstant(),
        checkpoint = getLong("checkpoint"),
        poisonReason = getString("poison_reason"),
        revision = getLong("revision"),
    )

    private fun currentConnection(): Connection =
        checkNotNull(TransactionManager.currentOrNull()?.connection?.connection as? Connection) {
            "voucher pool worker repository requires an active JDBC transaction"
        }

    private companion object {
        const val MAX_OWNER_LENGTH = 128
        const val MAX_BACKOFF_SHIFT = 30
        val BOUNDED_REASON = Regex("[A-Z0-9_]{1,64}")
    }
}
