package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGeneration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRebuildRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.findActive
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.findBuildingGeneration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.findGeneration
import java.time.Clock

private const val MIN_IDEMPOTENCY_KEY_LENGTH = 8
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
private const val STALE_TOKEN_REASON = "STALE_GENERATION_TOKEN"
private const val START_CONFLICT_REASON = "REBUILD_ALREADY_RUNNING"
private const val CANCEL_CONFLICT_REASON = "REBUILD_NOT_CANCELLABLE"
private const val RESUME_CONFLICT_REASON = "REBUILD_NOT_RESUMABLE"

internal data class RebuildRequestIdentity(
    val tenant: String,
    val principal: String,
    val idempotencyKey: String,
)

internal data class StartRebuildRequest(
    val identity: RebuildRequestIdentity,
    val projection: String,
    val targetPosition: Long,
    val expectedToken: Long,
)

internal data class RebuildGenerationRequest(
    val identity: RebuildRequestIdentity,
    val key: ProjectionKey,
    val expectedToken: Long,
)

internal sealed interface RebuildManagementResult {
    data class Accepted(
        val generation: ProjectionGeneration,
        val replayed: Boolean,
    ) : RebuildManagementResult

    data class StaleToken(val currentToken: Long) : RebuildManagementResult

    data class Conflict(
        val generation: ProjectionGeneration?,
        val reason: String,
    ) : RebuildManagementResult

    data object NotFound : RebuildManagementResult
}

private data class RebuildAuditMutation(
    val expectedToken: Long,
    val before: ProjectionGenerationState?,
    val after: ProjectionGenerationState?,
    val outcome: OperatorAuditOutcome,
    val reason: String? = null,
)

/**
 * Fenced rebuild control boundary. Every mutation and its bounded operator audit commit together.
 */
internal class EventSourcedRebuildManagementService(
    private val transactions: EventSourcedPermitTransactionRunner,
    private val rebuilds: ProjectionRebuildRepository,
    private val audits: OperatorAuditRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun start(request: StartRebuildRequest): RebuildManagementResult =
        transactions.inTransaction {
            val validRequest = request.validated()
            val identity = validRequest.identity.toAuditIdentity("REBUILD_START", validRequest.fingerprintMaterial())
            audits.find(identity.tenant, identity.requestDigest, OperatorAuditAction.REBUILD_STARTED)
                ?.let { return@inTransaction replay(it) }
            val active = checkNotNull(findActive(validRequest.projection)) { "active projection does not exist" }
            val activeGeneration =
                checkNotNull(findGeneration(ProjectionKey(active.projection, active.generation)))
            if (active.revision != validRequest.expectedToken) {
                return@inTransaction rejectStart(
                    validRequest,
                    identity,
                    activeGeneration,
                    STALE_TOKEN_REASON,
                    active.revision,
                )
            }
            findBuildingGeneration(validRequest.projection)?.let { building ->
                return@inTransaction rejectStart(
                    validRequest,
                    identity,
                    building,
                    START_CONFLICT_REASON,
                    building.fencingToken,
                )
            }
            val generation = rebuilds.start(validRequest.projection, validRequest.targetPosition, clock.instant())
            audits.append(
                audit(
                    identity = identity,
                    action = OperatorAuditAction.REBUILD_STARTED,
                    generation = generation,
                    mutation =
                        RebuildAuditMutation(
                            expectedToken = validRequest.expectedToken,
                            before = ProjectionGenerationState.ACTIVE,
                            after = generation.state,
                            outcome = OperatorAuditOutcome.APPLIED,
                        ),
                ),
            )
            RebuildManagementResult.Accepted(generation, replayed = false)
        }

    fun status(request: RebuildGenerationRequest): RebuildManagementResult =
        transactions.inTransaction {
            val validRequest = request.validated()
            validRequest.identity.toAuditIdentity("REBUILD_STATUS", validRequest.fingerprintMaterial())
            val generation = findGeneration(validRequest.key) ?: return@inTransaction RebuildManagementResult.NotFound
            if (generation.fencingToken != validRequest.expectedToken) {
                RebuildManagementResult.StaleToken(generation.fencingToken)
            } else {
                RebuildManagementResult.Accepted(generation, replayed = false)
            }
        }

    fun cancel(request: RebuildGenerationRequest): RebuildManagementResult =
        mutateGeneration(request, OperatorAuditAction.REBUILD_CANCELLED, CANCEL_CONFLICT_REASON) { generation ->
            if (generation.state !in CANCELLABLE_STATES) {
                generation
            } else {
                checkNotNull(rebuilds.requestCancellation(generation.key, clock.instant()))
            }
        }

    fun resume(request: RebuildGenerationRequest): RebuildManagementResult =
        mutateGeneration(request, OperatorAuditAction.REBUILD_RESUMED, RESUME_CONFLICT_REASON) { generation ->
            if (generation.state != ProjectionGenerationState.FAILED || !generation.retryableFailure) {
                generation
            } else {
                checkNotNull(rebuilds.resume(generation.key, clock.instant()))
            }
        }

    private fun mutateGeneration(
        request: RebuildGenerationRequest,
        action: OperatorAuditAction,
        conflictReason: String,
        mutation: (ProjectionGeneration) -> ProjectionGeneration,
    ): RebuildManagementResult =
        transactions.inTransaction {
            val validRequest = request.validated()
            val identity = validRequest.identity.toAuditIdentity(action.name, validRequest.fingerprintMaterial())
            audits.find(identity.tenant, identity.requestDigest, action)
                ?.let { return@inTransaction replay(it) }
            val before = findGeneration(validRequest.key) ?: return@inTransaction RebuildManagementResult.NotFound
            if (before.fencingToken != validRequest.expectedToken) {
                audits.append(
                    audit(
                        identity,
                        action,
                        before,
                        RebuildAuditMutation(
                            expectedToken = validRequest.expectedToken,
                            before = before.state,
                            after = before.state,
                            outcome = OperatorAuditOutcome.REJECTED,
                            reason = STALE_TOKEN_REASON,
                        ),
                    ),
                )
                return@inTransaction RebuildManagementResult.StaleToken(before.fencingToken)
            }
            val after = mutation(before)
            val applied = after.fencingToken != before.fencingToken
            audits.append(
                audit(
                    identity,
                    action,
                    after,
                    RebuildAuditMutation(
                        expectedToken = validRequest.expectedToken,
                        before = before.state,
                        after = after.state,
                        outcome = if (applied) OperatorAuditOutcome.APPLIED else OperatorAuditOutcome.REJECTED,
                        reason = if (applied) null else conflictReason,
                    ),
                ),
            )
            if (applied) {
                RebuildManagementResult.Accepted(after, replayed = false)
            } else {
                RebuildManagementResult.Conflict(after, conflictReason)
            }
        }

    private fun rejectStart(
        request: StartRebuildRequest,
        identity: OperatorAuditIdentity,
        generation: ProjectionGeneration,
        reason: String,
        currentToken: Long,
    ): RebuildManagementResult {
        audits.append(
            audit(
                identity,
                OperatorAuditAction.REBUILD_STARTED,
                generation,
                RebuildAuditMutation(
                    expectedToken = request.expectedToken,
                    before = generation.state,
                    after = generation.state,
                    outcome = OperatorAuditOutcome.REJECTED,
                    reason = reason,
                ),
            ),
        )
        return if (reason == STALE_TOKEN_REASON) {
            RebuildManagementResult.StaleToken(currentToken)
        } else {
            RebuildManagementResult.Conflict(generation, reason)
        }
    }

    private fun replay(entry: OperatorAuditEntry): RebuildManagementResult {
        val generation = findGeneration(ProjectionKey(entry.projection, entry.generation))
        return when {
            entry.outcome == OperatorAuditOutcome.APPLIED && generation != null ->
                RebuildManagementResult.Accepted(generation, replayed = true)
            entry.reasonClass == STALE_TOKEN_REASON ->
                RebuildManagementResult.StaleToken(
                    generation?.fencingToken ?: checkNotNull(findActive(entry.projection)).revision,
                )
            else -> RebuildManagementResult.Conflict(generation, entry.reasonClass ?: "REBUILD_CONFLICT")
        }
    }

    private fun audit(
        identity: OperatorAuditIdentity,
        action: OperatorAuditAction,
        generation: ProjectionGeneration,
        mutation: RebuildAuditMutation,
    ): OperatorAuditEntry =
        OperatorAuditEntry(
            identity = identity,
            action = action,
            target =
                OperatorAuditTarget(
                    generation.key.projection,
                    generation.key.generation,
                    mutation.expectedToken,
                ),
            transition =
                OperatorAuditTransition(
                    beforeState = mutation.before,
                    afterState = mutation.after,
                    checkpointPosition = generation.currentPosition,
                    streamPosition = generation.targetPosition,
                    reasonClass = mutation.reason,
                ),
            result = OperatorAuditResult(mutation.outcome, clock.instant()),
        )

    private companion object {
        val CANCELLABLE_STATES =
            setOf(ProjectionGenerationState.BUILDING, ProjectionGenerationState.VALIDATING)
    }
}

internal fun RebuildRequestIdentity.toAuditIdentity(
    action: String,
    fingerprintMaterial: String,
): OperatorAuditIdentity {
    val validTenant = tenant.requireNotBlank("tenant")
    val validPrincipal = principal.requireNotBlank("principal")
    idempotencyKey.length.requireInRange(
        MIN_IDEMPOTENCY_KEY_LENGTH,
        MAX_IDEMPOTENCY_KEY_LENGTH,
        "Idempotency-Key.length",
    )
    val validIdempotencyKey = idempotencyKey.requireNotBlank("idempotencyKey")
    return OperatorAuditIdentity(
        actorDigest = ReceiptDigest.sha256("voucher-operator-v1\u0000$validPrincipal"),
        tenant = validTenant,
        requestDigest =
            ReceiptDigest.sha256(
                listOf("voucher-rebuild-request-v1", validTenant, action, fingerprintMaterial, validIdempotencyKey)
                    .joinToString("\u0000"),
            ),
    )
}

private fun StartRebuildRequest.validated(): StartRebuildRequest =
    copy(
        projection = projection.requireNotBlank("projection"),
        targetPosition = targetPosition.requireZeroOrPositiveNumber("targetPosition"),
        expectedToken = expectedToken.requirePositiveNumber("expectedToken"),
    )

private fun RebuildGenerationRequest.validated(): RebuildGenerationRequest =
    copy(expectedToken = expectedToken.requirePositiveNumber("expectedToken"))

private fun StartRebuildRequest.fingerprintMaterial(): String =
    listOf(projection, targetPosition, expectedToken).joinToString("\u0000")

private fun RebuildGenerationRequest.fingerprintMaterial(): String =
    listOf(key.projection, key.generation, expectedToken).joinToString("\u0000")
