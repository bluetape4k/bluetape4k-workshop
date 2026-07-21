@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.worker

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.ExpectedReservation
import io.bluetape4k.workshop.commerce.voucherpool.persistence.ExpectedUserLimit
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAuditRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.WorkerCandidate
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

internal data class WorkerChunkOutcome(
    val claim: WorkerClaim?,
    val processed: Int,
    val effects: Int,
    val completed: Boolean,
)

internal enum class WorkerRunState {
    COMPLETED,
    NOT_ACQUIRED,
    CANCELLED,
    RETRYABLE,
    POISONED,
    LOST,
}

internal data class WorkerRunOutcome(
    val state: WorkerRunState,
    val processed: Int,
    val effects: Int,
    val claim: WorkerClaimSnapshot?,
    val reasonCode: String? = null,
)

internal data class WorkerRunRequest(
    val tenantId: String,
    val kind: WorkerKind,
    val scopeId: UUID,
    val owner: String,
    val requestedLimit: Int = DEFAULT_WORKER_LIMIT,
)

internal interface VoucherPoolWorkers {
    fun runChunk(claim: WorkerClaim, requestedLimit: Int = DEFAULT_WORKER_LIMIT): WorkerChunkOutcome
    fun run(
        request: WorkerRunRequest,
        continueRunning: () -> Boolean = { !Thread.currentThread().isInterrupted },
    ): WorkerRunOutcome
    fun completeCampaignRevocation(tenantId: String, campaignId: UUID): Boolean
}

internal class JdbcVoucherPoolWorkers(
    private val executor: VoucherPoolJdbcExecutor,
    private val claims: JdbcVoucherPoolWorkerRepository,
    private val repository: VoucherPoolRepository,
) : VoucherPoolWorkers {

    @Suppress("ReturnCount")
    override fun run(request: WorkerRunRequest, continueRunning: () -> Boolean): WorkerRunOutcome {
        var activeClaim = claims.claim(request.tenantId, request.kind, request.scopeId, request.owner)
            ?: return WorkerRunOutcome(
                WorkerRunState.NOT_ACQUIRED,
                0,
                0,
                claims.snapshot(request.tenantId, request.kind, request.scopeId),
                "CLAIM_NOT_ACQUIRED",
            )
        var processed = 0
        var effects = 0
        while (true) {
            if (!continueRunning()) {
                val released = claims.release(activeClaim)
                return WorkerRunOutcome(WorkerRunState.CANCELLED, processed, effects, released, "CANCELLED")
            }
            val chunk = try {
                runChunk(activeClaim, request.requestedLimit)
            } catch (failure: WorkerChunkExecutionException) {
                return failedRun(failure.claim, processed, effects, failure.reasonCode)
            } catch (failure: StaleWorkerClaimException) {
                return lostRun(activeClaim, processed, effects, failure.redactedReasonCode())
            } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
                return failedRun(activeClaim, processed, effects, failure.redactedReasonCode())
            }
            processed += chunk.processed
            effects += chunk.effects
            if (chunk.completed) {
                return WorkerRunOutcome(
                    WorkerRunState.COMPLETED,
                    processed,
                    effects,
                    claims.snapshot(request.tenantId, request.kind, request.scopeId),
                )
            }
            activeClaim = checkNotNull(chunk.claim)
            if (chunk.processed == 0 && chunk.effects == 0) {
                return failedRun(activeClaim, processed, effects, "RECONCILIATION_REQUIRED")
            }
        }
    }

    override fun runChunk(claim: WorkerClaim, requestedLimit: Int): WorkerChunkOutcome {
        require(claim.kind != WorkerKind.PURGE) { "purge retention belongs to Task 12" }
        val limit = requestedLimit.coerceAtMost(maximumLimit(claim.kind))
        require(limit > 0)
        return when (claim.kind) {
            WorkerKind.RECONCILIATION -> runReconciliation(claim, limit)
            WorkerKind.CAMPAIGN_REVOKE -> runCampaignRevoke(claim, limit)
            else -> runEntryChunk(claim, limit)
        }
    }

    private fun runEntryChunk(claim: WorkerClaim, limit: Int): WorkerChunkOutcome {
        var activeClaim = prepareBatchState(claim)
        val candidates = scanCandidates(activeClaim, limit)
        if (candidates.isEmpty()) return finalizeIfComplete(activeClaim)

        var effects = 0
        candidates.forEach { candidate ->
            try {
                activeClaim = executor.workerTransaction {
                    val connection = currentConnection()
                    claims.requireCurrentInTransaction(connection, activeClaim)
                    val chain = repository.lockCanonicalChain(connection, candidate.authority)
                    if (chain != null && apply(connection, activeClaim.kind, chain)) effects++
                    claims.checkpointInTransaction(connection, activeClaim, candidate.cursorAfter)
                }
            } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
                throw WorkerChunkExecutionException(activeClaim, failure)
            }
        }
        return WorkerChunkOutcome(activeClaim, candidates.size, effects, completed = false)
    }

    private fun failedRun(
        claim: WorkerClaim,
        processed: Int,
        effects: Int,
        reason: String = "WORKER_CHUNK_FAILED",
    ): WorkerRunOutcome = try {
        val failure = claims.fail(claim, reason)
        val state = if (failure.snapshot.state == WorkerClaimState.POISONED) {
            WorkerRunState.POISONED
        } else {
            WorkerRunState.RETRYABLE
        }
        WorkerRunOutcome(state, processed, effects, failure.snapshot, reason)
    } catch (stale: StaleWorkerClaimException) {
        lostRun(claim, processed, effects, stale.redactedReasonCode())
    }

    private fun lostRun(
        claim: WorkerClaim,
        processed: Int,
        effects: Int,
        reasonCode: String,
    ): WorkerRunOutcome =
        WorkerRunOutcome(
            WorkerRunState.LOST,
            processed,
            effects,
            claims.snapshot(claim.tenantId, claim.kind, claim.scopeId),
            reasonCode,
        )

    override fun completeCampaignRevocation(tenantId: String, campaignId: UUID): Boolean =
        executor.workerTransaction {
            completeCampaignRevocationInTransaction(currentConnection(), tenantId, campaignId)
        }

    private fun runCampaignRevoke(claim: WorkerClaim, limit: Int): WorkerChunkOutcome = executor.workerTransaction {
        val connection = currentConnection()
        claims.requireCurrentInTransaction(connection, claim)
        val campaign = lockCampaignScope(connection, claim.tenantId, claim.scopeId)
        check(campaign.state in setOf(CampaignState.REVOKING, CampaignState.REVOKED))
        if (campaign.state == CampaignState.REVOKED) {
            claims.completeInTransaction(connection, claim)
            return@workerTransaction WorkerChunkOutcome(null, 0, 0, completed = true)
        }
        val inserted = claims.ensureBatchRevokeClaimsForCampaignInTransaction(
            connection,
            claim.tenantId,
            claim.scopeId,
            limit,
        )
        val renewed = claims.checkpointInTransaction(connection, claim, claim.cursor + inserted)
        val more = claims.hasUnscheduledBatchRevokesInTransaction(connection, claim.tenantId, claim.scopeId)
        if (more) {
            WorkerChunkOutcome(renewed, inserted, inserted, completed = false)
        } else {
            completeCampaignRevocationInTransaction(connection, claim.tenantId, claim.scopeId)
            claims.completeInTransaction(connection, renewed)
            WorkerChunkOutcome(null, inserted, inserted, completed = true)
        }
    }

    private fun runReconciliation(claim: WorkerClaim, limit: Int): WorkerChunkOutcome = executor.workerTransaction {
        val connection = currentConnection()
        claims.requireCurrentInTransaction(connection, claim)
        val scope = lockBatchScope(connection, claim)
        var effects = reconcilePoolDepth(connection, claim)
        val users = lockUserLimitPage(connection, claim, scope.campaignId, limit)
        users.forEach { user -> if (reconcileUserLimit(connection, claim, scope.campaignId, user)) effects++ }
        if (effects > 0) {
            repository.appendAudit(
                connection,
                VoucherPoolAuditRecord(
                    claim.tenantId,
                    scope.campaignId,
                    "RECONCILIATION",
                    claim.scopeId,
                    claim.checkpoint + 1,
                    scope.policyVersion,
                    "WORKER",
                    "RECONCILED",
                    beforeCount = effects.toLong(),
                    afterCount = 0L,
                ),
            )
        }
        val renewed = claims.checkpointInTransaction(connection, claim, claim.cursor + users.size)
        if (users.size < limit) {
            claims.completeInTransaction(connection, renewed)
            WorkerChunkOutcome(null, users.size, effects, completed = true)
        } else {
            WorkerChunkOutcome(renewed, users.size, effects, completed = false)
        }
    }

    private fun prepareBatchState(claim: WorkerClaim): WorkerClaim {
        val transition = when (claim.kind) {
            WorkerKind.BATCH_REVOKE -> BatchState.REVOKING
            WorkerKind.BATCH_EXPIRY -> BatchState.EXPIRING
            else -> return claim
        }
        val terminal = when (claim.kind) {
            WorkerKind.BATCH_REVOKE -> BatchState.REVOKED
            WorkerKind.BATCH_EXPIRY -> BatchState.EXPIRED
        }
        return executor.workerTransaction {
            val connection = currentConnection()
            claims.requireCurrentInTransaction(connection, claim)
            val scope = lockBatchScope(connection, claim)
            if (scope.batchState != transition && scope.batchState != terminal) {
                if (transition == BatchState.EXPIRING) {
                    check(scope.expiresAt != null && scope.expiresAt <= transactionTime(connection)) {
                        "batch expiry is not due"
                    }
                }
                val allowed = when (transition) {
                    BatchState.REVOKING -> scope.batchState in setOf(
                        BatchState.STAGING,
                        BatchState.ACTIVE,
                        BatchState.PAUSED,
                        BatchState.FAILED_RETRYABLE,
                        BatchState.FAILED_TERMINAL,
                    )
                    BatchState.EXPIRING -> scope.batchState in setOf(BatchState.ACTIVE, BatchState.PAUSED)
                    else -> false
                }
                check(allowed) { "batch cannot enter ${transition.name} from ${scope.batchState.name}" }
                updateBatchState(connection, claim, scope.batchRevision, transition)
            }
            claims.checkpointInTransaction(connection, claim, claim.cursor)
        }
    }

    private fun scanCandidates(claim: WorkerClaim, limit: Int): List<WorkerEntryCandidate> = executor.workerTransaction {
        val connection = currentConnection()
        claims.requireCurrentInTransaction(connection, claim)
        val afterCursor = queryCandidates(connection, claim, claim.cursor, limit)
        if (afterCursor.isNotEmpty() || claim.cursor == 0L) afterCursor else queryCandidates(connection, claim, 0L, limit)
    }

    private fun queryCandidates(
        connection: Connection,
        claim: WorkerClaim,
        cursor: Long,
        limit: Int,
    ): List<WorkerEntryCandidate> {
        val predicate = when (claim.kind) {
            WorkerKind.RESERVATION_EXPIRY -> "e.state='RESERVED' AND e.reservation_expires_at<=transaction_timestamp()"
            WorkerKind.ALLOCATION_EXPIRY -> "e.state='ALLOCATED' AND e.allocation_expires_at<=transaction_timestamp()"
            WorkerKind.BATCH_REVOKE, WorkerKind.BATCH_EXPIRY -> "e.state IN ('AVAILABLE','RESERVED','ALLOCATED')"
            else -> return emptyList()
        }
        return connection.prepareStatement(
            """SELECT e.source_ordinal,e.tenant_id,e.campaign_id,e.batch_id,e.entry_id,e.revision AS entry_revision,
                       c.revision AS campaign_revision,b.revision AS batch_revision,
                       r.reservation_id,r.revision AS reservation_revision,
                       u.user_digest,u.revision AS user_limit_revision
                FROM voucher_pool_entries e
                JOIN voucher_pool_campaigns c ON c.tenant_id=e.tenant_id AND c.campaign_id=e.campaign_id
                JOIN voucher_pool_batches b ON b.tenant_id=e.tenant_id AND b.batch_id=e.batch_id
                LEFT JOIN voucher_pool_reservations r ON r.tenant_id=e.tenant_id AND r.reservation_id=e.reservation_id
                LEFT JOIN voucher_pool_user_limits u ON u.tenant_id=e.tenant_id AND u.campaign_id=e.campaign_id
                                                     AND u.user_digest=e.user_digest
                WHERE e.tenant_id=? AND e.batch_id=? AND e.source_ordinal>=? AND $predicate
                ORDER BY e.source_ordinal,e.entry_id LIMIT ?""",
        ).use { statement ->
            statement.setString(1, claim.tenantId)
            statement.setObject(2, claim.scopeId)
            statement.setLong(3, cursor)
            statement.setInt(4, limit)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.workerEntryCandidate()) } }
        }
    }

    private fun apply(
        connection: Connection,
        kind: WorkerKind,
        chain: io.bluetape4k.workshop.commerce.voucherpool.persistence.LockedWorkerChain,
    ): Boolean = when (kind) {
        WorkerKind.RESERVATION_EXPIRY -> repository.expireReservation(connection, chain)
        WorkerKind.ALLOCATION_EXPIRY -> repository.terminalizeWorkerEntry(
            connection, chain, EntryState.EXPIRED, "ALLOCATION_EXPIRED",
        )
        WorkerKind.BATCH_REVOKE -> repository.terminalizeWorkerEntry(
            connection, chain, EntryState.REVOKED, "BATCH_REVOKED",
        )
        WorkerKind.BATCH_EXPIRY -> repository.terminalizeWorkerEntry(
            connection, chain, EntryState.EXPIRED, "BATCH_EXPIRED",
        )
        else -> false
    }

    private fun finalizeIfComplete(claim: WorkerClaim): WorkerChunkOutcome = executor.workerTransaction {
        val connection = currentConnection()
        claims.requireCurrentInTransaction(connection, claim)
        if (claim.kind in setOf(WorkerKind.BATCH_REVOKE, WorkerKind.BATCH_EXPIRY)) {
            val scope = lockBatchScope(connection, claim)
            val remaining = activeEntryCount(connection, claim)
            if (remaining != 0L || !poolDepthMatches(connection, claim)) {
                return@workerTransaction WorkerChunkOutcome(claim, 0, 0, completed = false)
            }
            val terminal = if (claim.kind == WorkerKind.BATCH_REVOKE) BatchState.REVOKED else BatchState.EXPIRED
            if (scope.batchState != terminal) {
                updateBatchState(connection, claim, scope.batchRevision, terminal)
                repository.appendAudit(
                    connection,
                    VoucherPoolAuditRecord(
                        claim.tenantId,
                        scope.campaignId,
                        "BATCH",
                        claim.scopeId,
                        scope.batchRevision + 1,
                        scope.policyVersion,
                        "WORKER",
                        terminal.name,
                    ),
                )
            }
            if (claim.kind == WorkerKind.BATCH_REVOKE) {
                completeCampaignRevocationInTransaction(connection, claim.tenantId, scope.campaignId)
            }
        }
        claims.completeInTransaction(connection, claim)
        WorkerChunkOutcome(null, 0, 0, completed = true)
    }

    private fun completeCampaignRevocationInTransaction(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
    ): Boolean {
        val campaign = lockCampaignScope(connection, tenantId, campaignId)
        return when {
            campaign.state == CampaignState.REVOKED -> true
            campaign.state != CampaignState.REVOKING -> false
            campaignRevocationRemaining(connection, tenantId, campaignId) != 0L -> false
            else -> {
                updateCampaignState(connection, tenantId, campaignId, campaign.revision, CampaignState.REVOKED)
                appendCampaignAudit(connection, tenantId, campaignId, campaign, CampaignState.REVOKED)
                true
            }
        }
    }

    private fun campaignRevocationRemaining(connection: Connection, tenantId: String, campaignId: UUID): Long =
        connection.prepareStatement(
            """SELECT count(*) FROM voucher_pool_batches
                WHERE tenant_id=? AND campaign_id=? AND state NOT IN ('REVOKED','EXPIRED')""",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, campaignId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }

    private fun lockBatchScope(connection: Connection, claim: WorkerClaim): LockedBatchScope {
        val campaignId = connection.prepareStatement(
            "SELECT campaign_id FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=?",
        ).use { statement ->
            statement.setString(1, claim.tenantId)
            statement.setObject(2, claim.scopeId)
            statement.executeQuery().use { result -> check(result.next()); result.getObject(1, UUID::class.java) }
        }
        connection.prepareStatement(
            "SELECT campaign_id FROM voucher_pool_campaigns WHERE tenant_id=? AND campaign_id=? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, claim.tenantId)
            statement.setObject(2, campaignId)
            statement.executeQuery().use { result -> check(result.next()) }
        }
        return connection.prepareStatement(
            "SELECT state,revision,policy_version,expires_at FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, claim.tenantId)
            statement.setObject(2, claim.scopeId)
            statement.executeQuery().use { result ->
                check(result.next())
                LockedBatchScope(
                    campaignId,
                    BatchState.valueOf(result.getString(1)),
                    result.getLong(2),
                    result.getLong(3),
                    result.getTimestamp(4)?.toInstant(),
                )
            }
        }
    }

    private fun updateBatchState(
        connection: Connection,
        claim: WorkerClaim,
        expectedRevision: Long,
        state: BatchState,
    ) {
        connection.prepareStatement(
            "UPDATE voucher_pool_batches SET state=?,revision=revision+1 WHERE tenant_id=? AND batch_id=? AND revision=?",
        ).use { statement ->
            statement.setString(1, state.name)
            statement.setString(2, claim.tenantId)
            statement.setObject(3, claim.scopeId)
            statement.setLong(4, expectedRevision)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun activeEntryCount(connection: Connection, claim: WorkerClaim): Long = connection.prepareStatement(
        "SELECT count(*) FROM voucher_pool_entries WHERE tenant_id=? AND batch_id=? AND state IN ('AVAILABLE','RESERVED','ALLOCATED')",
    ).use { statement ->
        statement.setString(1, claim.tenantId)
        statement.setObject(2, claim.scopeId)
        statement.executeQuery().use { result -> result.next(); result.getLong(1) }
    }

    private fun poolDepthMatches(connection: Connection, claim: WorkerClaim): Boolean = connection.prepareStatement(
        """SELECT NOT EXISTS (
              SELECT state,count(*) AS actual FROM voucher_pool_entries WHERE tenant_id=? AND batch_id=? GROUP BY state
              EXCEPT
              SELECT state,entry_count FROM voucher_pool_pool_depth WHERE tenant_id=? AND batch_id=? AND entry_count<>0
            ) AND NOT EXISTS (
              SELECT state,entry_count FROM voucher_pool_pool_depth WHERE tenant_id=? AND batch_id=? AND entry_count<>0
              EXCEPT
              SELECT state,count(*) FROM voucher_pool_entries WHERE tenant_id=? AND batch_id=? GROUP BY state
            )""",
    ).use { statement ->
        repeat(4) { index ->
            statement.setString(index * 2 + 1, claim.tenantId)
            statement.setObject(index * 2 + 2, claim.scopeId)
        }
        statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
    }

    private fun reconcilePoolDepth(connection: Connection, claim: WorkerClaim): Int = connection.prepareStatement(
        """SELECT d.state,d.entry_count,d.revision,
                  (SELECT count(*) FROM voucher_pool_entries e
                   WHERE e.tenant_id=d.tenant_id AND e.batch_id=d.batch_id AND e.state=d.state) AS actual
            FROM voucher_pool_pool_depth d
            WHERE d.tenant_id=? AND d.batch_id=? ORDER BY d.state FOR UPDATE OF d""",
    ).use { statement ->
        statement.setString(1, claim.tenantId)
        statement.setObject(2, claim.scopeId)
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    add(DepthProjection(result.getString(1), result.getLong(2), result.getLong(3), result.getLong(4)))
                }
            }
        }.count { depth ->
            if (depth.stored == depth.actual) return@count false
            connection.prepareStatement(
                """UPDATE voucher_pool_pool_depth SET entry_count=?,revision=revision+1
                    WHERE tenant_id=? AND batch_id=? AND state=? AND revision=?""",
            ).use { update ->
                update.setLong(1, depth.actual)
                update.setString(2, claim.tenantId)
                update.setObject(3, claim.scopeId)
                update.setString(4, depth.state)
                update.setLong(5, depth.revision)
                check(update.executeUpdate() == 1)
            }
            true
        }
    }

    private fun lockUserLimitPage(
        connection: Connection,
        claim: WorkerClaim,
        campaignId: UUID,
        limit: Int,
    ): List<UserLimitProjection> = connection.prepareStatement(
        """SELECT u.user_digest,u.active_reservations,u.active_allocations,u.lifetime_consumed,u.revision,
                  (SELECT count(*) FROM voucher_pool_reservations r
                   WHERE r.tenant_id=u.tenant_id AND r.campaign_id=u.campaign_id
                     AND r.user_digest=u.user_digest AND r.state='ACTIVE') AS actual_reservations,
                  (SELECT count(*) FROM voucher_pool_entries e
                   WHERE e.tenant_id=u.tenant_id AND e.campaign_id=u.campaign_id
                     AND e.user_digest=u.user_digest AND e.state='ALLOCATED') AS actual_allocations,
                  (SELECT count(*) FROM voucher_pool_allocations a
                   WHERE a.tenant_id=u.tenant_id AND a.campaign_id=u.campaign_id
                     AND a.user_digest=u.user_digest AND a.replacement_ordinal=0) AS actual_lifetime
            FROM voucher_pool_user_limits u
            WHERE u.tenant_id=? AND u.campaign_id=?
            ORDER BY encode(u.user_digest,'hex') OFFSET ? LIMIT ? FOR UPDATE OF u""",
    ).use { statement ->
        statement.setString(1, claim.tenantId)
        statement.setObject(2, campaignId)
        statement.setLong(3, claim.cursor)
        statement.setInt(4, limit)
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    add(
                        UserLimitProjection(
                            result.getBytes(1),
                            result.getInt(2),
                            result.getInt(3),
                            result.getInt(4),
                            result.getLong(5),
                            result.getInt(6),
                            result.getInt(7),
                            result.getInt(8),
                        ),
                    )
                }
            }
        }
    }

    private fun reconcileUserLimit(
        connection: Connection,
        claim: WorkerClaim,
        campaignId: UUID,
        user: UserLimitProjection,
    ): Boolean {
        if (user.matches()) return false
        connection.prepareStatement(
            """UPDATE voucher_pool_user_limits
                SET active_reservations=?,active_allocations=?,lifetime_consumed=?,revision=revision+1
                WHERE tenant_id=? AND campaign_id=? AND user_digest=? AND revision=?""",
        ).use { statement ->
            statement.setInt(1, user.actualReservations)
            statement.setInt(2, user.actualAllocations)
            statement.setInt(3, user.actualLifetime)
            statement.setString(4, claim.tenantId)
            statement.setObject(5, campaignId)
            statement.setBytes(6, user.digest)
            statement.setLong(7, user.revision)
            check(statement.executeUpdate() == 1)
        }
        return true
    }

    private fun lockCampaignScope(connection: Connection, tenantId: String, campaignId: UUID): LockedCampaignScope =
        connection.prepareStatement(
            """SELECT state,revision,policy_version FROM voucher_pool_campaigns
                WHERE tenant_id=? AND campaign_id=? FOR UPDATE""",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, campaignId)
            statement.executeQuery().use { result ->
                check(result.next())
                LockedCampaignScope(CampaignState.valueOf(result.getString(1)), result.getLong(2), result.getLong(3))
            }
        }

    private fun updateCampaignState(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
        expectedRevision: Long,
        state: CampaignState,
    ) {
        connection.prepareStatement(
            """UPDATE voucher_pool_campaigns SET state=?,revision=revision+1
                WHERE tenant_id=? AND campaign_id=? AND revision=?""",
        ).use { statement ->
            statement.setString(1, state.name)
            statement.setString(2, tenantId)
            statement.setObject(3, campaignId)
            statement.setLong(4, expectedRevision)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun appendCampaignAudit(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
        campaign: LockedCampaignScope,
        state: CampaignState,
    ) {
        repository.appendAudit(
            connection,
            VoucherPoolAuditRecord(
                tenantId,
                campaignId,
                "CAMPAIGN",
                campaignId,
                campaign.revision + 1,
                campaign.policyVersion,
                "WORKER",
                state.name,
            ),
        )
    }

    private fun ResultSet.workerEntryCandidate(): WorkerEntryCandidate {
        val reservationId = getObject("reservation_id", UUID::class.java)
        val userDigest = getBytes("user_digest")
        val authority = WorkerCandidate(
            tenantId = getString("tenant_id"),
            campaignId = getObject("campaign_id", UUID::class.java),
            batchId = getObject("batch_id", UUID::class.java),
            entryId = getObject("entry_id", UUID::class.java),
            expectedCampaignRevision = getLong("campaign_revision"),
            expectedBatchRevision = getLong("batch_revision"),
            expectedEntryRevision = getLong("entry_revision"),
            userLimits = if (userDigest == null) emptyList() else listOf(ExpectedUserLimit(DigestValue.of(userDigest), getLong("user_limit_revision"))),
            reservations = if (reservationId == null) emptyList() else listOf(ExpectedReservation(reservationId, getLong("reservation_revision"))),
        )
        return WorkerEntryCandidate(authority, getLong("source_ordinal") + 1)
    }

    private fun maximumLimit(kind: WorkerKind): Int =
        if (kind in setOf(WorkerKind.RECONCILIATION, WorkerKind.CAMPAIGN_REVOKE)) 50 else 100

    private fun currentConnection(): Connection =
        checkNotNull(TransactionManager.currentOrNull()?.connection?.connection as? Connection)

    private fun transactionTime(connection: Connection): Instant = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT transaction_timestamp()").use { result ->
            result.next()
            result.getTimestamp(1).toInstant()
        }
    }
}

private data class WorkerEntryCandidate(val authority: WorkerCandidate, val cursorAfter: Long)

private class WorkerChunkExecutionException(
    val claim: WorkerClaim,
    cause: RuntimeException,
) : RuntimeException("worker chunk failed", cause) {
    val reasonCode: String = cause.redactedReasonCode()
}

private fun RuntimeException.redactedReasonCode(): String = when (this) {
    is StaleWorkerClaimException -> "STALE_CLAIM"
    is VoucherPoolJdbcTimeoutException -> "WORKER_TIMEOUT"
    else -> "WORKER_CHUNK_FAILED"
}

private data class LockedBatchScope(
    val campaignId: UUID,
    val batchState: BatchState,
    val batchRevision: Long,
    val policyVersion: Long,
    val expiresAt: Instant?,
)

private data class LockedCampaignScope(
    val state: CampaignState,
    val revision: Long,
    val policyVersion: Long,
)

private data class DepthProjection(
    val state: String,
    val stored: Long,
    val revision: Long,
    val actual: Long,
)

private data class UserLimitProjection(
    val digest: ByteArray,
    val storedReservations: Int,
    val storedAllocations: Int,
    val storedLifetime: Int,
    val revision: Long,
    val actualReservations: Int,
    val actualAllocations: Int,
    val actualLifetime: Int,
) {
    fun matches(): Boolean =
        storedReservations == actualReservations &&
            storedAllocations == actualAllocations &&
            storedLifetime == actualLifetime
}

internal const val DEFAULT_WORKER_LIMIT = 100
