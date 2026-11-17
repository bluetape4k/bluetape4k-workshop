@file:Suppress("LargeClass", "LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.worker

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.ExpectedReservation
import io.bluetape4k.workshop.commerce.voucherpool.persistence.ExpectedUserLimit
import io.bluetape4k.workshop.commerce.voucherpool.persistence.LockedWorkerChain
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAuditRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAllocationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBatchTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCampaignTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolEntryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolDepthTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolReservationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolUserLimitTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import io.bluetape4k.workshop.commerce.voucherpool.persistence.WorkerCandidate
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.time.Instant
import java.util.*

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
): VoucherPoolWorkers {

    companion object: KLogging()

    @Suppress("ReturnCount")
    override fun run(request: WorkerRunRequest, continueRunning: () -> Boolean): WorkerRunOutcome {
        var activeClaim = claims.claim(request.tenantId, request.kind, request.scopeId, request.owner)
            ?: return WorkerRunOutcome(
                state = WorkerRunState.NOT_ACQUIRED,
                processed = 0,
                effects = 0,
                claim = claims.snapshot(request.tenantId, request.kind, request.scopeId),
                reasonCode = "CLAIM_NOT_ACQUIRED",
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
            else                      -> runEntryChunk(claim, limit)
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
                    val chain = repository.lockCanonicalChain(candidate.authority)
                    if (chain != null && apply(activeClaim.kind, chain)) effects++
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
            state = WorkerRunState.LOST,
            processed = processed,
            effects = effects,
            claim = claims.snapshot(claim.tenantId, claim.kind, claim.scopeId),
            reasonCode = reasonCode,
        )

    override fun completeCampaignRevocation(tenantId: String, campaignId: UUID): Boolean =
        executor.workerTransaction {
            completeCampaignRevocationInTransaction(tenantId, campaignId)
        }

    private fun runCampaignRevoke(claim: WorkerClaim, limit: Int): WorkerChunkOutcome = executor.workerTransaction {
        val connection = currentConnection()
        claims.requireCurrentInTransaction(connection, claim)
        val campaign = lockCampaignScope(claim.tenantId, claim.scopeId)
        check(campaign.state in setOf(CampaignState.REVOKING, CampaignState.REVOKED))

        if (campaign.state == CampaignState.REVOKED) {
            claims.completeInTransaction(connection, claim)
            return@workerTransaction WorkerChunkOutcome(null, 0, 0, completed = true)
        }
        val inserted = claims.ensureBatchRevokeClaimsForCampaignInTransaction(
            tenantId = claim.tenantId,
            campaignId = claim.scopeId,
            limit = limit,
        )
        val renewed = claims.checkpointInTransaction(connection, claim, claim.cursor + inserted)
        val more = claims.hasUnscheduledBatchRevokesInTransaction(claim.tenantId, claim.scopeId)

        if (more) {
            WorkerChunkOutcome(renewed, inserted, inserted, completed = false)
        } else {
            completeCampaignRevocationInTransaction(claim.tenantId, claim.scopeId)
            claims.completeInTransaction(connection, renewed)
            WorkerChunkOutcome(null, inserted, inserted, completed = true)
        }
    }

    private fun runReconciliation(claim: WorkerClaim, limit: Int): WorkerChunkOutcome = executor.workerTransaction {
        val connection = currentConnection()
        claims.requireCurrentInTransaction(connection, claim)
        val scope = lockBatchScope(claim)
        var effects = reconcilePoolDepth(claim)
        val users = lockUserLimitPage(claim, scope.campaignId, limit)
        users.forEach { user ->
            if (reconcileUserLimit(claim, scope.campaignId, user)) {
                effects++
            }
        }

        if (effects > 0) {
            repository.appendAudit(
                VoucherPoolAuditRecord(
                    tenantId = claim.tenantId,
                    campaignId = scope.campaignId,
                    aggregateType = "RECONCILIATION",
                    aggregateId = claim.scopeId,
                    revision = claim.checkpoint + 1,
                    policyVersion = scope.policyVersion,
                    actorType = "WORKER",
                    reasonCode = "RECONCILED",
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
            val scope = lockBatchScope(claim)
            if (scope.batchState != transition && scope.batchState != terminal) {
                if (transition == BatchState.EXPIRING) {
                    check(scope.expiresAt != null && scope.expiresAt <= transactionTime(connection)) {
                        "batch expiry is not due"
                    }
                }
                val allowed: Boolean = when (transition) {
                    BatchState.REVOKING ->
                        scope.batchState in setOf(
                            BatchState.STAGING,
                            BatchState.ACTIVE,
                            BatchState.PAUSED,
                            BatchState.FAILED_RETRYABLE,
                            BatchState.FAILED_TERMINAL,
                        )
                    BatchState.EXPIRING -> scope.batchState in setOf(BatchState.ACTIVE, BatchState.PAUSED)
                    else                -> false
                }
                check(allowed) { "batch cannot enter ${transition.name} from ${scope.batchState.name}" }
                updateBatchState(claim, scope.batchRevision, transition)
            }
            claims.checkpointInTransaction(connection, claim, claim.cursor)
        }
    }

    private fun scanCandidates(claim: WorkerClaim, limit: Int): List<WorkerEntryCandidate> =
        executor.workerTransaction {
            val connection = currentConnection()
            claims.requireCurrentInTransaction(connection, claim)
            val afterCursor = queryCandidates(claim, claim.cursor, limit)
            if (afterCursor.isNotEmpty() || claim.cursor == 0L) afterCursor else queryCandidates(claim, 0L, limit)
        }

    private fun queryCandidates(
        claim: WorkerClaim,
        cursor: Long,
        limit: Int,
    ): List<WorkerEntryCandidate> {
        val statePredicate = when (claim.kind) {
            WorkerKind.RESERVATION_EXPIRY ->
                (VoucherPoolEntryTable.state eq EntryState.RESERVED) and
                        (VoucherPoolEntryTable.reservationExpiresAt lessEq CurrentTimestamp)

            WorkerKind.ALLOCATION_EXPIRY ->
                (VoucherPoolEntryTable.state eq EntryState.ALLOCATED) and
                        (VoucherPoolEntryTable.allocationExpiresAt lessEq CurrentTimestamp)

            WorkerKind.BATCH_REVOKE, WorkerKind.BATCH_EXPIRY ->
                VoucherPoolEntryTable.state inList listOf(
                    EntryState.AVAILABLE,
                    EntryState.RESERVED,
                    EntryState.ALLOCATED,
                )

            else -> return emptyList()
        }
        val joined = VoucherPoolEntryTable
            .innerJoin(
                VoucherPoolCampaignTable,
                { VoucherPoolEntryTable.tenantId },
                { VoucherPoolCampaignTable.tenantId },
                additionalConstraint = { VoucherPoolEntryTable.campaignId eq VoucherPoolCampaignTable.campaignId },
            )
            .innerJoin(
                VoucherPoolBatchTable,
                { VoucherPoolEntryTable.tenantId },
                { VoucherPoolBatchTable.tenantId },
                additionalConstraint = {
                    (VoucherPoolEntryTable.batchId eq VoucherPoolBatchTable.batchId) and
                            (VoucherPoolEntryTable.campaignId eq VoucherPoolBatchTable.campaignId)
                },
            )
        return joined
            .select(
                VoucherPoolEntryTable.sourceOrdinal,
                VoucherPoolEntryTable.tenantId,
                VoucherPoolEntryTable.campaignId,
                VoucherPoolEntryTable.batchId,
                VoucherPoolEntryTable.entryId,
                VoucherPoolEntryTable.revision,
                VoucherPoolEntryTable.reservationId,
                VoucherPoolEntryTable.userDigest,
                VoucherPoolCampaignTable.revision,
                VoucherPoolBatchTable.revision,
            )
            .where {
                (VoucherPoolEntryTable.tenantId eq claim.tenantId) and
                        (VoucherPoolEntryTable.batchId eq claim.scopeId) and
                        (VoucherPoolEntryTable.sourceOrdinal greaterEq cursor) and
                        statePredicate
            }
            .orderBy(
                VoucherPoolEntryTable.sourceOrdinal to SortOrder.ASC,
                VoucherPoolEntryTable.entryId to SortOrder.ASC,
            )
            .limit(limit)
            .map { row ->
                val reservationId = row[VoucherPoolEntryTable.reservationId]
                val userDigest = row[VoucherPoolEntryTable.userDigest]
                val reservationRevision = reservationId?.let { id ->
                    VoucherPoolReservationTable
                        .select(VoucherPoolReservationTable.revision)
                        .where {
                            (VoucherPoolReservationTable.tenantId eq claim.tenantId) and
                                    (VoucherPoolReservationTable.reservationId eq id)
                        }
                        .singleOrNull()
                        ?.get(VoucherPoolReservationTable.revision)
                } ?: 0L
                val userLimitRevision = userDigest?.let { digest ->
                    VoucherPoolUserLimitTable
                        .select(VoucherPoolUserLimitTable.revision)
                        .where {
                            (VoucherPoolUserLimitTable.tenantId eq claim.tenantId) and
                                    (VoucherPoolUserLimitTable.campaignId eq row[VoucherPoolEntryTable.campaignId]) and
                                    (VoucherPoolUserLimitTable.userDigest eq digest)
                        }
                        .singleOrNull()
                        ?.get(VoucherPoolUserLimitTable.revision)
                } ?: 0L
                WorkerEntryCandidate(
                    authority = WorkerCandidate(
                        tenantId = row[VoucherPoolEntryTable.tenantId],
                        campaignId = row[VoucherPoolEntryTable.campaignId],
                        batchId = row[VoucherPoolEntryTable.batchId],
                        entryId = row[VoucherPoolEntryTable.entryId],
                        expectedCampaignRevision = row[VoucherPoolCampaignTable.revision],
                        expectedBatchRevision = row[VoucherPoolBatchTable.revision],
                        expectedEntryRevision = row[VoucherPoolEntryTable.revision],
                        userLimits = if (userDigest == null) emptyList() else listOf(
                            ExpectedUserLimit(
                                userDigest = DigestValue.of(userDigest),
                                expectedRevision = userLimitRevision,
                            ),
                        ),
                        reservations = if (reservationId == null) emptyList() else listOf(
                            ExpectedReservation(
                                reservationId = reservationId,
                                expectedRevision = reservationRevision,
                            ),
                        ),
                    ),
                    cursorAfter = row[VoucherPoolEntryTable.sourceOrdinal] + 1,
                )
            }
    }

    private fun apply(
        kind: WorkerKind,
        chain: LockedWorkerChain,
    ): Boolean = when (kind) {
        WorkerKind.RESERVATION_EXPIRY -> repository.expireReservation(chain)
        WorkerKind.ALLOCATION_EXPIRY -> repository.terminalizeWorkerEntry(
            chain, EntryState.EXPIRED, "ALLOCATION_EXPIRED",
        )
        WorkerKind.BATCH_REVOKE      -> repository.terminalizeWorkerEntry(
            chain, EntryState.REVOKED, "BATCH_REVOKED",
        )
        WorkerKind.BATCH_EXPIRY      -> repository.terminalizeWorkerEntry(
            chain, EntryState.EXPIRED, "BATCH_EXPIRED",
        )
        else                         -> false
    }

    private fun finalizeIfComplete(claim: WorkerClaim): WorkerChunkOutcome =
        executor.workerTransaction {
            val connection = currentConnection()
            claims.requireCurrentInTransaction(connection, claim)
            if (claim.kind in setOf(WorkerKind.BATCH_REVOKE, WorkerKind.BATCH_EXPIRY)) {
                val scope = lockBatchScope(claim)
                val remaining = activeEntryCount(claim)
                if (remaining != 0L || !poolDepthMatches(claim)) {
                    return@workerTransaction WorkerChunkOutcome(claim, 0, 0, completed = false)
                }
                val terminal = if (claim.kind == WorkerKind.BATCH_REVOKE) BatchState.REVOKED else BatchState.EXPIRED
                if (scope.batchState != terminal) {
                    updateBatchState(claim, scope.batchRevision, terminal)
                    repository.appendAudit(
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
                    completeCampaignRevocationInTransaction(claim.tenantId, scope.campaignId)
                }
            }
            claims.completeInTransaction(connection, claim)
            WorkerChunkOutcome(null, 0, 0, completed = true)
        }

    private fun completeCampaignRevocationInTransaction(
        tenantId: String,
        campaignId: UUID,
    ): Boolean {
        val campaign = lockCampaignScope(tenantId, campaignId)
        return when {
            campaign.state == CampaignState.REVOKED  -> true
            campaign.state != CampaignState.REVOKING -> false
            campaignRevocationRemaining(tenantId, campaignId) != 0L -> false
            else                                     -> {
                updateCampaignState(tenantId, campaignId, campaign.revision, CampaignState.REVOKED)
                appendCampaignAudit(tenantId, campaignId, campaign, CampaignState.REVOKED)
                true
            }
        }
    }

    private fun campaignRevocationRemaining(tenantId: String, campaignId: UUID): Long =
        VoucherPoolBatchTable
            .selectAll()
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.campaignId eq campaignId) and
                        (VoucherPoolBatchTable.state notInList listOf(BatchState.REVOKED, BatchState.EXPIRED))
            }
            .count()

    private fun lockBatchScope(claim: WorkerClaim): LockedBatchScope {
        val campaignId = VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.campaignId)
            .where {
                (VoucherPoolBatchTable.tenantId eq claim.tenantId) and
                        (VoucherPoolBatchTable.batchId eq claim.scopeId)
            }
            .single()
            .get(VoucherPoolBatchTable.campaignId)
        VoucherPoolCampaignTable
            .select(VoucherPoolCampaignTable.campaignId)
            .where {
                (VoucherPoolCampaignTable.tenantId eq claim.tenantId) and
                        (VoucherPoolCampaignTable.campaignId eq campaignId)
            }
            .forUpdate()
            .single()
        val batch = VoucherPoolBatchTable
            .selectAll()
            .where {
                (VoucherPoolBatchTable.tenantId eq claim.tenantId) and
                        (VoucherPoolBatchTable.batchId eq claim.scopeId)
            }
            .forUpdate()
            .single()
        return LockedBatchScope(
            campaignId = campaignId,
            batchState = batch[VoucherPoolBatchTable.state],
            batchRevision = batch[VoucherPoolBatchTable.revision],
            policyVersion = batch[VoucherPoolBatchTable.policyVersion],
            expiresAt = batch[VoucherPoolBatchTable.expiresAt],
        )
    }

    private fun updateBatchState(
        claim: WorkerClaim,
        expectedRevision: Long,
        state: BatchState,
    ) {
        val updated = VoucherPoolBatchTable.update({
            (VoucherPoolBatchTable.tenantId eq claim.tenantId) and
                    (VoucherPoolBatchTable.batchId eq claim.scopeId) and
                    (VoucherPoolBatchTable.revision eq expectedRevision)
        }) {
            it[VoucherPoolBatchTable.state] = state
            it[VoucherPoolBatchTable.revision] = VoucherPoolBatchTable.revision + 1
        }
        check(updated == 1)
    }

    private fun activeEntryCount(claim: WorkerClaim): Long =
        VoucherPoolEntryTable
            .selectAll()
            .where {
                (VoucherPoolEntryTable.tenantId eq claim.tenantId) and
                        (VoucherPoolEntryTable.batchId eq claim.scopeId) and
                        (VoucherPoolEntryTable.state inList listOf(EntryState.AVAILABLE, EntryState.RESERVED, EntryState.ALLOCATED))
            }
            .count()

    private fun poolDepthMatches(claim: WorkerClaim): Boolean {
        val actualCount = VoucherPoolEntryTable.entryId.count()
        val actual = VoucherPoolEntryTable
            .select(VoucherPoolEntryTable.state, actualCount)
            .where {
                (VoucherPoolEntryTable.tenantId eq claim.tenantId) and
                        (VoucherPoolEntryTable.batchId eq claim.scopeId)
            }
            .groupBy(VoucherPoolEntryTable.state)
            .associate { row -> row[VoucherPoolEntryTable.state] to row[actualCount] }
        val stored = VoucherPoolDepthTable
            .select(VoucherPoolDepthTable.state, VoucherPoolDepthTable.entryCount)
            .where {
                (VoucherPoolDepthTable.tenantId eq claim.tenantId) and
                        (VoucherPoolDepthTable.batchId eq claim.scopeId) and
                        (VoucherPoolDepthTable.entryCount neq 0L)
            }
            .associate { row -> row[VoucherPoolDepthTable.state] to row[VoucherPoolDepthTable.entryCount] }
        return actual == stored
    }

    private fun reconcilePoolDepth(claim: WorkerClaim): Int {
        val depthRows = VoucherPoolDepthTable
            .select(
                VoucherPoolDepthTable.state,
                VoucherPoolDepthTable.entryCount,
                VoucherPoolDepthTable.revision,
            )
            .where {
                (VoucherPoolDepthTable.tenantId eq claim.tenantId) and
                        (VoucherPoolDepthTable.batchId eq claim.scopeId)
            }
            .orderBy(VoucherPoolDepthTable.state to SortOrder.ASC)
            .forUpdate()

        return depthRows.count { row ->
            val state = row[VoucherPoolDepthTable.state]
            val actual = VoucherPoolEntryTable
                .selectAll()
                .where {
                    (VoucherPoolEntryTable.tenantId eq claim.tenantId) and
                            (VoucherPoolEntryTable.batchId eq claim.scopeId) and
                            (VoucherPoolEntryTable.state eq state)
                }
                .count()
            if (row[VoucherPoolDepthTable.entryCount] == actual) return@count false

            val updated = VoucherPoolDepthTable.update({
                (VoucherPoolDepthTable.tenantId eq claim.tenantId) and
                        (VoucherPoolDepthTable.batchId eq claim.scopeId) and
                        (VoucherPoolDepthTable.state eq state) and
                        (VoucherPoolDepthTable.revision eq row[VoucherPoolDepthTable.revision])
            }) {
                it[VoucherPoolDepthTable.entryCount] = actual
                it[VoucherPoolDepthTable.revision] = VoucherPoolDepthTable.revision + 1
            }
            check(updated == 1)
            true
        }
    }

    private fun lockUserLimitPage(
        claim: WorkerClaim,
        campaignId: UUID,
        limit: Int,
    ): List<UserLimitProjection> =
        VoucherPoolUserLimitTable
            .selectAll()
            .where {
                (VoucherPoolUserLimitTable.tenantId eq claim.tenantId) and
                        (VoucherPoolUserLimitTable.campaignId eq campaignId)
            }
            .orderBy(VoucherPoolUserLimitTable.userDigest to SortOrder.ASC)
            .offset(claim.cursor)
            .limit(limit)
            .forUpdate()
            .map { row ->
                val digest = row[VoucherPoolUserLimitTable.userDigest]
                UserLimitProjection(
                    digest = digest,
                    storedReservations = row[VoucherPoolUserLimitTable.activeReservations],
                    storedAllocations = row[VoucherPoolUserLimitTable.activeAllocations],
                    storedLifetime = row[VoucherPoolUserLimitTable.lifetimeConsumed],
                    revision = row[VoucherPoolUserLimitTable.revision],
                    actualReservations = VoucherPoolReservationTable
                        .selectAll()
                        .where {
                            (VoucherPoolReservationTable.tenantId eq claim.tenantId) and
                                    (VoucherPoolReservationTable.campaignId eq campaignId) and
                                    (VoucherPoolReservationTable.userDigest eq digest) and
                                    (VoucherPoolReservationTable.state eq ReservationState.ACTIVE)
                        }
                        .count()
                        .toInt(),
                    actualAllocations = VoucherPoolEntryTable
                        .selectAll()
                        .where {
                            (VoucherPoolEntryTable.tenantId eq claim.tenantId) and
                                    (VoucherPoolEntryTable.campaignId eq campaignId) and
                                    (VoucherPoolEntryTable.userDigest eq digest) and
                                    (VoucherPoolEntryTable.state eq EntryState.ALLOCATED)
                        }
                        .count()
                        .toInt(),
                    actualLifetime = VoucherPoolAllocationTable
                        .selectAll()
                        .where {
                            (VoucherPoolAllocationTable.tenantId eq claim.tenantId) and
                                    (VoucherPoolAllocationTable.campaignId eq campaignId) and
                                    (VoucherPoolAllocationTable.userDigest eq digest) and
                                    (VoucherPoolAllocationTable.replacementOrdinal eq 0)
                        }
                        .count()
                        .toInt(),
                )
            }

    private fun reconcileUserLimit(
        claim: WorkerClaim,
        campaignId: UUID,
        user: UserLimitProjection,
    ): Boolean {
        if (user.matches()) return false
        val updated = VoucherPoolUserLimitTable.update({
            (VoucherPoolUserLimitTable.tenantId eq claim.tenantId) and
                    (VoucherPoolUserLimitTable.campaignId eq campaignId) and
                    (VoucherPoolUserLimitTable.userDigest eq user.digest) and
                    (VoucherPoolUserLimitTable.revision eq user.revision)
        }) {
            it[VoucherPoolUserLimitTable.activeReservations] = user.actualReservations
            it[VoucherPoolUserLimitTable.activeAllocations] = user.actualAllocations
            it[VoucherPoolUserLimitTable.lifetimeConsumed] = user.actualLifetime
            it[VoucherPoolUserLimitTable.revision] = VoucherPoolUserLimitTable.revision + 1
        }
        check(updated == 1)
        return true
    }

    private fun lockCampaignScope(tenantId: String, campaignId: UUID): LockedCampaignScope =
        VoucherPoolCampaignTable
            .select(
                VoucherPoolCampaignTable.state,
                VoucherPoolCampaignTable.revision,
                VoucherPoolCampaignTable.policyVersion,
            )
            .where {
                (VoucherPoolCampaignTable.tenantId eq tenantId) and
                        (VoucherPoolCampaignTable.campaignId eq campaignId)
            }
            .forUpdate()
            .single()
            .let { row ->
                LockedCampaignScope(
                    state = row[VoucherPoolCampaignTable.state],
                    revision = row[VoucherPoolCampaignTable.revision],
                    policyVersion = row[VoucherPoolCampaignTable.policyVersion],
                )
            }

    private fun updateCampaignState(
        tenantId: String,
        campaignId: UUID,
        expectedRevision: Long,
        state: CampaignState,
    ) {
        val updated = VoucherPoolCampaignTable.update({
            (VoucherPoolCampaignTable.tenantId eq tenantId) and
                    (VoucherPoolCampaignTable.campaignId eq campaignId) and
                    (VoucherPoolCampaignTable.revision eq expectedRevision)
        }) {
            it[VoucherPoolCampaignTable.state] = state
            it[VoucherPoolCampaignTable.revision] = VoucherPoolCampaignTable.revision + 1
        }
        check(updated == 1)
    }

    private fun appendCampaignAudit(
        tenantId: String,
        campaignId: UUID,
        campaign: LockedCampaignScope,
        state: CampaignState,
    ) {
        repository.appendAudit(
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

    private fun maximumLimit(kind: WorkerKind): Int =
        if (kind in setOf(WorkerKind.RECONCILIATION, WorkerKind.CAMPAIGN_REVOKE)) 50 else 100

    private fun currentConnection(): Connection =
        TransactionManager.currentOrNull()
            ?.connection
            ?.connection as? Connection ?: error("voucher pool worker repository requires an active JDBC transaction")

    /** worker entry transition과 같은 PostgreSQL transaction snapshot을 사용해야 하므로 raw timestamp 조회를 유지합니다. */
    private fun transactionTime(connection: Connection): Instant =
        connection.createStatement().use { statement ->
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
): RuntimeException("worker chunk failed", cause) {
    val reasonCode: String = cause.redactedReasonCode()
}

private fun RuntimeException.redactedReasonCode(): String = when (this) {
    is StaleWorkerClaimException -> "STALE_CLAIM"
    is VoucherPoolJdbcTimeoutException -> "WORKER_TIMEOUT"
    else                         -> "WORKER_CHUNK_FAILED"
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
