@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.worker

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBatchTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolWorkerClaimTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.*

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
    val lease: Duration = 15.seconds(),
    val runDeadline: Duration = 30.seconds(),
    val maxAttempts: Int = 5,
    val maximumBackoff: Duration = 30.seconds(),
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
            attempt > 0   -> WorkerClaimState.RETRYABLE
            else          -> WorkerClaimState.IDLE
        }

    val nextAction: String
        get() = when (state) {
            WorkerClaimState.POISONED -> "OPERATOR_REVIEW_REQUIRED"
            WorkerClaimState.RETRYABLE -> "RETRY_AFTER_BACKOFF"
            WorkerClaimState.OWNED    -> "WAIT_FOR_CHECKPOINT"
            WorkerClaimState.IDLE     -> "CLAIM_AVAILABLE"
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

internal data class WorkerClaimCandidate(
    val tenantId: String,
    val kind: WorkerKind,
    val scopeId: UUID,
)

internal class StaleWorkerClaimException: IllegalStateException("worker claim owner or revision is stale")

internal interface VoucherPoolWorkerRepository {
    fun findRunnable(limit: Int): List<WorkerClaimCandidate>
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
): VoucherPoolWorkerRepository {

    private companion object: KLogging() {
        const val MAX_OWNER_LENGTH = 128
        const val MAX_BACKOFF_SHIFT = 30
        const val MAX_RUNNABLE_CANDIDATES = 64
        val COMPLETED_NEXT_ATTEMPT: Instant = Instant.parse("9999-12-31T23:59:59Z")
        val BOUNDED_REASON = Regex("[A-Z0-9_]{1,64}")
    }

    override fun findRunnable(limit: Int): List<WorkerClaimCandidate> {
        require(limit in 1..MAX_RUNNABLE_CANDIDATES)
        return executor.workerTransaction {
            VoucherPoolWorkerClaimTable
                .selectAll()
                .where {
                    VoucherPoolWorkerClaimTable.poisonReason.isNull() and
                            (VoucherPoolWorkerClaimTable.nextAttemptAt lessEq org.jetbrains.exposed.v1.javatime.CurrentTimestamp) and
                            (VoucherPoolWorkerClaimTable.ownerId.isNull() or
                                    (VoucherPoolWorkerClaimTable.claimUntil lessEq org.jetbrains.exposed.v1.javatime.CurrentTimestamp))
                }
                .orderBy(
                    VoucherPoolWorkerClaimTable.nextAttemptAt to SortOrder.ASC,
                    VoucherPoolWorkerClaimTable.workerType to SortOrder.ASC,
                    VoucherPoolWorkerClaimTable.tenantId to SortOrder.ASC,
                    VoucherPoolWorkerClaimTable.scopeId to SortOrder.ASC,
                )
                .limit(limit)
                .map {
                    WorkerClaimCandidate(
                        tenantId = it[VoucherPoolWorkerClaimTable.tenantId],
                        kind = WorkerKind.valueOf(it[VoucherPoolWorkerClaimTable.workerType]),
                        scopeId = it[VoucherPoolWorkerClaimTable.scopeId],
                    )
                }
        }
    }

    override fun claim(tenantId: String, kind: WorkerKind, scopeId: UUID, owner: String): WorkerClaim? {
        require(tenantId.isNotBlank())
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH)

        return executor.workerTransaction {
            val connection = currentConnection()
            insertClaimIfMissing(tenantId, kind, scopeId)
            val current = lockSnapshot(tenantId, kind, scopeId) ?: return@workerTransaction null
            val now = transactionTime(connection)
            if (current.poisonReason != null) return@workerTransaction null
            if (current.nextAttemptAt > now && current.nextAttemptAt != COMPLETED_NEXT_ATTEMPT) {
                return@workerTransaction null
            }
            if (current.owner != null && checkNotNull(current.claimUntil) > now) return@workerTransaction null
            val updated = VoucherPoolWorkerClaimTable.update({
                (VoucherPoolWorkerClaimTable.tenantId eq tenantId) and
                        (VoucherPoolWorkerClaimTable.workerType eq kind.name) and
                        (VoucherPoolWorkerClaimTable.scopeId eq scopeId) and
                        (VoucherPoolWorkerClaimTable.revision eq current.revision)
            }) {
                it[VoucherPoolWorkerClaimTable.ownerId] = owner
                it[VoucherPoolWorkerClaimTable.claimUntil] = now.plus(policy.lease)
                it[VoucherPoolWorkerClaimTable.revision] = VoucherPoolWorkerClaimTable.revision + 1
            }
            check(updated == 1)
            checkNotNull(lockSnapshot(tenantId, kind, scopeId)).ownedClaim(now + policy.runDeadline)
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

        val updated = VoucherPoolWorkerClaimTable.update({
            (VoucherPoolWorkerClaimTable.tenantId eq claim.tenantId) and
                    (VoucherPoolWorkerClaimTable.workerType eq claim.kind.name) and
                    (VoucherPoolWorkerClaimTable.scopeId eq claim.scopeId) and
                    (VoucherPoolWorkerClaimTable.revision eq current.revision) and
                    (VoucherPoolWorkerClaimTable.ownerId eq claim.owner)
        }) {
            it[VoucherPoolWorkerClaimTable.cursor] = cursor
            it[VoucherPoolWorkerClaimTable.checkpoint] = VoucherPoolWorkerClaimTable.checkpoint + 1
            it[VoucherPoolWorkerClaimTable.attempt] = 0
            it[VoucherPoolWorkerClaimTable.nextAttemptAt] = now
            it[VoucherPoolWorkerClaimTable.claimUntil] = now.plus(policy.lease)
            it[VoucherPoolWorkerClaimTable.revision] = VoucherPoolWorkerClaimTable.revision + 1
        }
        check(updated == 1)
        return checkNotNull(
            lockSnapshot(
                claim.tenantId,
                claim.kind,
                claim.scopeId
            )
        ).ownedClaim(claim.runDeadline)
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

            val updated = VoucherPoolWorkerClaimTable.update({
                (VoucherPoolWorkerClaimTable.tenantId eq claim.tenantId) and
                        (VoucherPoolWorkerClaimTable.workerType eq claim.kind.name) and
                        (VoucherPoolWorkerClaimTable.scopeId eq claim.scopeId) and
                        (VoucherPoolWorkerClaimTable.revision eq current.revision) and
                        (VoucherPoolWorkerClaimTable.ownerId eq claim.owner)
            }) {
                it[VoucherPoolWorkerClaimTable.ownerId] = null
                it[VoucherPoolWorkerClaimTable.claimUntil] = null
                it[VoucherPoolWorkerClaimTable.attempt] = nextAttempt
                it[VoucherPoolWorkerClaimTable.nextAttemptAt] = now.plusSeconds(backoffSeconds)
                it[VoucherPoolWorkerClaimTable.poisonReason] = poisonReason
                it[VoucherPoolWorkerClaimTable.revision] = VoucherPoolWorkerClaimTable.revision + 1
            }
            check(updated == 1)
            WorkerFailure(
                checkNotNull(lockSnapshot(claim.tenantId, claim.kind, claim.scopeId)),
                backoffSeconds,
            )
        }
    }

    override fun release(claim: WorkerClaim): WorkerClaimSnapshot = complete(claim, resetCursor = false)

    override fun finalize(claim: WorkerClaim): WorkerClaimSnapshot = complete(claim, resetCursor = true)

    override fun snapshot(tenantId: String, kind: WorkerKind, scopeId: UUID): WorkerClaimSnapshot? =
        executor.workerTransaction { selectSnapshot(tenantId, kind, scopeId, forUpdate = false) }

    internal fun ensureClaimInTransaction(
        tenantId: String,
        kind: WorkerKind,
        scopeId: UUID,
    ): Boolean = insertClaimIfMissing(tenantId, kind, scopeId)

    internal fun scheduleReconciliationInTransaction(
        tenantId: String,
        scopeId: UUID,
    ): Boolean {
        return when {
            insertClaimIfMissing(tenantId, WorkerKind.RECONCILIATION, scopeId) -> true
            else -> {
                val current = lockSnapshot(tenantId, WorkerKind.RECONCILIATION, scopeId)
                val completed = current != null &&
                        current.owner == null &&
                        current.cursor == 0L &&
                        current.attempt == 0 &&
                        current.poisonReason == null &&
                        current.checkpoint > 0L
                if (!completed) {
                    false
                } else {
                    VoucherPoolWorkerClaimTable.update({
                        (VoucherPoolWorkerClaimTable.tenantId eq tenantId) and
                                (VoucherPoolWorkerClaimTable.workerType eq WorkerKind.RECONCILIATION.name) and
                                (VoucherPoolWorkerClaimTable.scopeId eq scopeId) and
                                (VoucherPoolWorkerClaimTable.revision eq checkNotNull(current).revision)
                    }) {
                        it[VoucherPoolWorkerClaimTable.checkpoint] = 0
                        it[VoucherPoolWorkerClaimTable.nextAttemptAt] =
                            org.jetbrains.exposed.v1.javatime.CurrentTimestamp
                        it[VoucherPoolWorkerClaimTable.revision] = VoucherPoolWorkerClaimTable.revision + 1
                    } == 1
                }
            }
        }
    }

    internal fun ensureBatchRevokeClaimsForCampaignInTransaction(
        tenantId: String,
        campaignId: UUID,
        limit: Int,
    ): Int {
        val batchIds = VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.batchId)
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.campaignId eq campaignId) and
                        (VoucherPoolBatchTable.state notInList listOf(BatchState.REVOKED, BatchState.EXPIRED))
            }
            .orderBy(VoucherPoolBatchTable.batchId to SortOrder.ASC)
            .map { it[VoucherPoolBatchTable.batchId] }
        var inserted = 0
        for (batchId in batchIds) {
            if (insertClaimIfMissing(tenantId, WorkerKind.BATCH_REVOKE, batchId)) inserted++
            if (inserted == limit) break
        }
        return inserted
    }

    internal fun hasUnscheduledBatchRevokesInTransaction(
        tenantId: String,
        campaignId: UUID,
    ): Boolean {
        val batchIds = VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.batchId)
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.campaignId eq campaignId) and
                        (VoucherPoolBatchTable.state notInList listOf(BatchState.REVOKED, BatchState.EXPIRED))
            }
            .map { it[VoucherPoolBatchTable.batchId] }
        return batchIds.any { batchId ->
            VoucherPoolWorkerClaimTable
                .select(VoucherPoolWorkerClaimTable.scopeId)
                .where {
                    (VoucherPoolWorkerClaimTable.tenantId eq tenantId) and
                            (VoucherPoolWorkerClaimTable.workerType eq WorkerKind.BATCH_REVOKE.name) and
                            (VoucherPoolWorkerClaimTable.scopeId eq batchId)
                }
                .singleOrNull() == null
        }
    }

    private fun complete(claim: WorkerClaim, resetCursor: Boolean): WorkerClaimSnapshot =
        executor.workerTransaction {
            completeInTransaction(currentConnection(), claim, resetCursor)
        }

    internal fun completeInTransaction(
        connection: Connection,
        claim: WorkerClaim,
        resetCursor: Boolean = true,
    ): WorkerClaimSnapshot {
        val current = requireCurrentOwner(connection, claim)
        val now = transactionTime(connection)
        val updated = VoucherPoolWorkerClaimTable.update({
            (VoucherPoolWorkerClaimTable.tenantId eq claim.tenantId) and
                    (VoucherPoolWorkerClaimTable.workerType eq claim.kind.name) and
                    (VoucherPoolWorkerClaimTable.scopeId eq claim.scopeId) and
                    (VoucherPoolWorkerClaimTable.revision eq current.revision) and
                    (VoucherPoolWorkerClaimTable.ownerId eq claim.owner)
        }) {
            it[VoucherPoolWorkerClaimTable.ownerId] = null
            it[VoucherPoolWorkerClaimTable.claimUntil] = null
            it[VoucherPoolWorkerClaimTable.cursor] = if (resetCursor) 0L else current.cursor
            it[VoucherPoolWorkerClaimTable.nextAttemptAt] = if (resetCursor) COMPLETED_NEXT_ATTEMPT else now
            it[VoucherPoolWorkerClaimTable.revision] = VoucherPoolWorkerClaimTable.revision + 1
        }
        check(updated == 1)
        return checkNotNull(lockSnapshot(claim.tenantId, claim.kind, claim.scopeId))
    }

    private fun requireCurrentOwner(connection: Connection, claim: WorkerClaim): WorkerClaimSnapshot {
        val current = lockSnapshot(claim.tenantId, claim.kind, claim.scopeId)
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

    private fun insertClaimIfMissing(
        tenantId: String,
        kind: WorkerKind,
        scopeId: UUID,
    ): Boolean = VoucherPoolWorkerClaimTable.insertIgnore {
        it[VoucherPoolWorkerClaimTable.tenantId] = tenantId
        it[VoucherPoolWorkerClaimTable.workerType] = kind.name
        it[VoucherPoolWorkerClaimTable.scopeId] = scopeId
    }.insertedCount == 1

    private fun lockSnapshot(
        tenantId: String,
        kind: WorkerKind,
        scopeId: UUID,
    ): WorkerClaimSnapshot? =
        selectSnapshot(tenantId, kind, scopeId, forUpdate = true)

    private fun selectSnapshot(
        tenantId: String,
        kind: WorkerKind,
        scopeId: UUID,
        forUpdate: Boolean,
    ): WorkerClaimSnapshot? {
        val query = VoucherPoolWorkerClaimTable
            .selectAll()
            .where {
                (VoucherPoolWorkerClaimTable.tenantId eq tenantId) and
                        (VoucherPoolWorkerClaimTable.workerType eq kind.name) and
                        (VoucherPoolWorkerClaimTable.scopeId eq scopeId)
            }
        if (forUpdate) query.forUpdate()
        return query.singleOrNull()?.workerClaimSnapshot()
    }

    /** worker CAS의 모든 deadline이 PostgreSQL transaction snapshot과 같은 시각을 보도록 raw 조회를 유지합니다. */
    private fun transactionTime(connection: Connection): Instant =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT transaction_timestamp()")
                .use { result ->
                    result.next()
                    result.getTimestamp(1).toInstant()
                }
        }

    private fun WorkerClaimSnapshot.ownedClaim(deadline: Instant): WorkerClaim =
        WorkerClaim(
            tenantId = tenantId,
            kind = kind,
            scopeId = scopeId,
            owner = checkNotNull(owner),
            claimUntil = checkNotNull(claimUntil),
            cursor = cursor,
            attempt = attempt,
            nextAttemptAt = nextAttemptAt,
            checkpoint = checkpoint,
            revision = revision,
            runDeadline = deadline,
        )

    private fun ResultRow.workerClaimSnapshot(): WorkerClaimSnapshot =
        WorkerClaimSnapshot(
            tenantId = this[VoucherPoolWorkerClaimTable.tenantId],
            kind = WorkerKind.valueOf(this[VoucherPoolWorkerClaimTable.workerType]),
            scopeId = this[VoucherPoolWorkerClaimTable.scopeId],
            owner = this[VoucherPoolWorkerClaimTable.ownerId],
            claimUntil = this[VoucherPoolWorkerClaimTable.claimUntil],
            cursor = this[VoucherPoolWorkerClaimTable.cursor],
            attempt = this[VoucherPoolWorkerClaimTable.attempt],
            nextAttemptAt = this[VoucherPoolWorkerClaimTable.nextAttemptAt],
            checkpoint = this[VoucherPoolWorkerClaimTable.checkpoint],
            poisonReason = this[VoucherPoolWorkerClaimTable.poisonReason],
            revision = this[VoucherPoolWorkerClaimTable.revision],
        )

    private fun currentConnection(): Connection =
        TransactionManager.currentOrNull()
            ?.connection
            ?.connection as? Connection ?: error("voucher pool worker repository requires an active JDBC transaction")
}
