package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.MAX_PROJECTION_POISON_ATTEMPTS
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGeneration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionLease
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionLeaseRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonRecord
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionReconciliationSnapshot
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRecoveryStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.findGeneration
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class PoisonRetryRequest(
    val identity: RebuildRequestIdentity,
    val key: ProjectionKey,
    val eventId: UUID,
    val expectedToken: Long,
)

internal data class ProjectionReconciliationRequest(
    val identity: RebuildRequestIdentity,
    val key: ProjectionKey,
    val expectedToken: Long,
)

internal sealed interface PoisonRetryResult {
    data class Resolved(
        val poison: ProjectionPoisonRecord,
        val checkpointPosition: Long,
        val replayed: Boolean,
    ) : PoisonRetryResult

    data class RetryLater(val delay: Duration) : PoisonRetryResult

    data class StaleToken(val currentToken: Long) : PoisonRetryResult

    data class Conflict(val reason: String) : PoisonRetryResult

    data object NotFound : PoisonRetryResult
}

internal sealed interface ProjectionReconciliationResult {
    data class Completed(
        val snapshot: ProjectionReconciliationSnapshot,
        val replayed: Boolean,
    ) : ProjectionReconciliationResult

    data class StaleToken(val currentToken: Long) : ProjectionReconciliationResult

    data object NotFound : ProjectionReconciliationResult
}

internal data class ProjectionRecoveryPersistence(
    val projections: ProjectionRepository,
    val leases: ProjectionLeaseRepository,
    val poisons: ProjectionPoisonStore,
    val recovery: ProjectionRecoveryStore,
    val audits: OperatorAuditRepository,
)

private data class PoisonRetryResources(
    val event: EventEnvelope,
    val lease: ProjectionLease,
    val generation: ProjectionGeneration,
)

internal class ProjectionRecoveryManagementService(
    private val transactions: EventSourcedPermitTransactionRunner,
    private val persistence: ProjectionRecoveryPersistence,
    private val clock: Clock,
) {
    fun retryPoison(request: PoisonRetryRequest): PoisonRetryResult =
        transactions.inTransaction {
            val expectedToken = request.expectedToken.requirePositiveNumber("expectedToken")
            val auditIdentity =
                request.identity.toAuditIdentity(
                    "POISON_RETRY",
                    listOf(request.key.projection, request.key.generation, request.eventId, expectedToken)
                        .joinToString("\u0000"),
                )
            replayedPoisonRetry(request, auditIdentity)?.let { return@inTransaction it }
            val generation = findGeneration(request.key) ?: return@inTransaction PoisonRetryResult.NotFound
            if (generation.fencingToken != expectedToken) {
                return@inTransaction PoisonRetryResult.StaleToken(generation.fencingToken)
            }
            val poison =
                persistence.poisons.find(request.key, request.eventId)
                    ?: return@inTransaction PoisonRetryResult.NotFound
            val now = clock.instant()
            poisonRetryBlocker(poison, now)?.let { return@inTransaction it }
            applyPoisonRetry(request, expectedToken, generation, auditIdentity, now)
        }

    private fun replayedPoisonRetry(
        request: PoisonRetryRequest,
        identity: OperatorAuditIdentity,
    ): PoisonRetryResult.Resolved? {
        val audit =
            persistence.audits.find(
                identity.tenant,
                identity.requestDigest,
                OperatorAuditAction.POISON_RETRIED,
            ) ?: return null
        check(audit.result.outcome == OperatorAuditOutcome.APPLIED)
        return persistence.poisons.find(request.key, request.eventId)?.let { poison ->
            PoisonRetryResult.Resolved(
                poison = poison,
                checkpointPosition = persistence.projections.checkpoint(request.key)?.position ?: 0L,
                replayed = true,
            )
        }
    }

    private fun poisonRetryBlocker(
        poison: ProjectionPoisonRecord,
        now: Instant,
    ): PoisonRetryResult? =
        when {
            poison.state != ProjectionPoisonState.FAILED ->
                PoisonRetryResult.Conflict("POISON_ALREADY_RESOLVED")
            poison.attempts >= MAX_PROJECTION_POISON_ATTEMPTS ->
                PoisonRetryResult.Conflict("POISON_RETRY_EXHAUSTED")
            now.isBefore(poison.nextRetryAt) ->
                PoisonRetryResult.RetryLater(Duration.between(now, poison.nextRetryAt))
            else -> null
        }

    private fun applyPoisonRetry(
        request: PoisonRetryRequest,
        expectedToken: Long,
        generation: ProjectionGeneration,
        auditIdentity: OperatorAuditIdentity,
        now: Instant,
    ): PoisonRetryResult {
        val event =
            persistence.recovery.event(request.eventId)
                ?: return PoisonRetryResult.NotFound
        val lease =
            persistence.leases.acquire(
                request.key.projection,
                request.key.generation,
                auditIdentity.actorDigest.value,
                now,
            )
        return lease?.let {
            resolvePoisonRetry(
                request,
                expectedToken,
                auditIdentity,
                now,
                PoisonRetryResources(event, it, generation),
            )
        } ?: PoisonRetryResult.Conflict("PROJECTION_LEASE_UNAVAILABLE")
    }

    private fun resolvePoisonRetry(
        request: PoisonRetryRequest,
        expectedToken: Long,
        auditIdentity: OperatorAuditIdentity,
        now: Instant,
        resources: PoisonRetryResources,
    ): PoisonRetryResult.Resolved {
        val applied =
            persistence.projections.resolvePoison(
                request.key,
                resources.lease,
                resources.event,
                now,
            )
        val resolved = checkNotNull(persistence.poisons.find(request.key, request.eventId))
        persistence.audits.append(
            OperatorAuditEntry(
                identity = auditIdentity,
                action = OperatorAuditAction.POISON_RETRIED,
                    target = OperatorAuditTarget(request.key.projection, request.key.generation, expectedToken),
                    transition =
                        OperatorAuditTransition(
                            beforeState = resources.generation.state,
                            afterState = resources.generation.state,
                        checkpointPosition = applied.checkpoint.position,
                        streamPosition = resources.event.globalPosition,
                        reasonClass = null,
                    ),
                result = OperatorAuditResult(OperatorAuditOutcome.APPLIED, now),
            ),
        )
        log.info {
            "projection_poison_resolved projection=${request.key.projection} " +
                "generation=${request.key.generation} position=${resources.event.globalPosition}"
        }
        return PoisonRetryResult.Resolved(resolved, applied.checkpoint.position, replayed = false)
    }

    fun reconcile(request: ProjectionReconciliationRequest): ProjectionReconciliationResult =
        transactions.inTransaction {
            val expectedToken = request.expectedToken.requirePositiveNumber("expectedToken")
            val auditIdentity =
                request.identity.toAuditIdentity(
                    "RECONCILIATION",
                    listOf(request.key.projection, request.key.generation, expectedToken)
                        .joinToString("\u0000"),
                )
            val generation = findGeneration(request.key) ?: return@inTransaction ProjectionReconciliationResult.NotFound
            if (generation.fencingToken != expectedToken) {
                log.warn {
                    "projection_reconciliation_stale projection=${request.key.projection} " +
                        "generation=${request.key.generation}"
                }
                return@inTransaction ProjectionReconciliationResult.StaleToken(generation.fencingToken)
            }
            val replayed =
                persistence.audits.find(
                    auditIdentity.tenant,
                    auditIdentity.requestDigest,
                    OperatorAuditAction.RECONCILIATION_RUN,
                ) != null
            val snapshot = persistence.recovery.reconciliation(request.key)
            if (!replayed) {
                persistence.audits.append(
                    OperatorAuditEntry(
                        identity = auditIdentity,
                        action = OperatorAuditAction.RECONCILIATION_RUN,
                        target =
                            OperatorAuditTarget(
                                request.key.projection,
                                request.key.generation,
                                expectedToken,
                            ),
                        transition =
                            OperatorAuditTransition(
                                beforeState = generation.state,
                                afterState = generation.state,
                                checkpointPosition = snapshot.checkpointPosition,
                                streamPosition = snapshot.streamPosition,
                                reasonClass = null,
                            ),
                        result = OperatorAuditResult(OperatorAuditOutcome.APPLIED, clock.instant()),
                    ),
                )
            }
            ProjectionReconciliationResult.Completed(snapshot, replayed)
        }

    private companion object : KLogging()
}
