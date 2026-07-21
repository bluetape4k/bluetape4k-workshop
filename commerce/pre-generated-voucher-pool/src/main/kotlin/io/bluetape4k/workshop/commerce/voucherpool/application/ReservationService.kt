@file:Suppress(
    "LongParameterList",
    "LongMethod", // Reservation keeps the ordered campaign, user-limit, candidate, effect, and audit transaction visible.
    "MagicNumber",
    "MaxLineLength",
    "TooGenericExceptionCaught", // Unexpected runtime failures must release the idempotency owner before propagating.
)

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.DescriptorAction
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCatalog
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.EffectReference
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyOwner
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.BatchRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.ReservationRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAuditRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal class VoucherPoolLifecycleException(
    val code: VoucherPoolErrorCode,
    val safeEffectId: UUID? = null,
    val safeRevision: Long? = null,
) : IllegalStateException(code.name)

internal data class ReserveVoucherCommand(
    val tenantId: String,
    val campaignId: UUID,
    val canonicalUser: String,
    val idempotencyKey: String,
    val reservationId: UUID = UUID.randomUUID(),
)

internal data class ReleaseReservationCommand(
    val tenantId: String,
    val campaignId: UUID,
    val reservationId: UUID,
    val canonicalUser: String,
    val expectedRevision: Long,
    val idempotencyKey: String,
)

internal data class ReservationSnapshot(
    val tenantId: String,
    val reservationId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val state: ReservationState,
    val expiresAt: Instant,
    val entitlementRootId: UUID?,
    val replacementOrdinal: Int,
    val policyVersion: Long,
    val revision: Long,
)

internal interface ReservationService {
    fun reserve(command: ReserveVoucherCommand): MutationResult<ReservationSnapshot>
    fun release(command: ReleaseReservationCommand): MutationResult<ReservationSnapshot>
}

internal class JdbcReservationService(
    private val executor: VoucherPoolJdbcExecutor,
    private val repository: VoucherPoolRepository,
    idempotency: VoucherPoolIdempotencyRepository,
    private val digests: VoucherDigestService,
) : ReservationService {
    private val mutations = LifecycleMutationExecutor(executor, idempotency)

    override fun reserve(command: ReserveVoucherCommand): MutationResult<ReservationSnapshot> =
        mutations.execute(
            command.tenantId,
            "voucher-reserve",
            command.idempotencyKey,
            fingerprint(
                "voucher-reserve",
                "campaignId" to command.campaignId,
                "canonicalUser" to command.canonicalUser,
            ),
            command.reservationId,
            LIFECYCLE_HTTP_CREATED,
            LifecycleLane.FOREGROUND,
        ) { connection, owner ->
            val keyVersion = repository.userIdentityKeyVersion(command.tenantId, command.campaignId)
                ?: fail(VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE)
            val userDigest = digests.userIdentity(
                command.tenantId, command.campaignId, command.canonicalUser, keyVersion,
            )
            val guards = repository.lockReservationGuards(
                command.tenantId, command.campaignId, userDigest.copyBytes(),
            ) ?: fail(VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE)
            requireActive(guards.campaign.state)
            val transactionTime = repository.transactionTime()
            if (transactionTime < guards.campaign.startsAt || transactionTime >= guards.campaign.endsAt) {
                fail(VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE)
            }
            val lockedEligibleBatchIds = eligibleBatchIds(guards.batches, transactionTime)
            if (lockedEligibleBatchIds.isEmpty()) failForUnavailableBatches(guards.batches, transactionTime)
            val used = guards.userLimit.activeReservations + guards.userLimit.activeAllocations
            if (used >= guards.campaign.perUserLimit || guards.userLimit.lifetimeConsumed >= guards.campaign.perUserLimit) {
                fail(VoucherPoolErrorCode.USER_LIMIT_REACHED)
            }
            val entry = repository.selectAvailableEntrySkipLocked(
                command.tenantId,
                command.campaignId,
                lockedEligibleBatchIds,
            )
                ?: fail(
                    if (repository.hasAvailableEligibleEntry(
                            command.tenantId,
                            command.campaignId,
                            lockedEligibleBatchIds,
                        )
                    ) {
                        VoucherPoolErrorCode.POOL_BUSY
                    } else {
                        VoucherPoolErrorCode.POOL_EXHAUSTED
                    },
                )
            val expiresAt = transactionTime.plusSeconds(guards.campaign.reservationTtlSeconds)
            val saved = repository.createReservation(
                ReservationRecord(
                    command.tenantId,
                    command.reservationId,
                    command.campaignId,
                    entry.batchId,
                    entry.entryId,
                    DigestValue.of(userDigest.copyBytes()),
                    DigestValue.of(owner.copyScopedKeyDigest()),
                    ReservationState.ACTIVE.name,
                    expiresAt,
                    policyVersion = guards.campaign.policyVersion,
                    revision = 0,
                ),
                entry,
                guards.userLimit,
            )
            repository.appendLifecycleAudit(
                saved.tenantId,
                saved.campaignId,
                "RESERVATION",
                saved.reservationId,
                saved.revision,
                saved.policyVersion,
                "RESERVED",
                "CUSTOMER",
            )
            saved.snapshot()
        }

    private fun eligibleBatchIds(batches: List<BatchRecord>, transactionTime: Instant): List<UUID> =
        batches.filter { batch ->
            batch.state == BatchState.ACTIVE &&
                batch.activatesAt <= transactionTime &&
                (batch.expiresAt == null || batch.expiresAt > transactionTime)
        }.map { it.batchId }

    private fun failForUnavailableBatches(batches: List<BatchRecord>, transactionTime: Instant): Nothing {
        batches.firstOrNull { it.state != BatchState.ACTIVE }?.let { requireBatchUsable(it.state) }
        if (batches.any { it.expiresAt?.let { expiry -> expiry <= transactionTime } == true }) {
            fail(VoucherPoolErrorCode.BATCH_EXPIRED)
        }
        fail(VoucherPoolErrorCode.POOL_EXHAUSTED)
    }

    override fun release(command: ReleaseReservationCommand): MutationResult<ReservationSnapshot> =
        mutations.execute(
            command.tenantId,
            "voucher-reservation-release",
            command.idempotencyKey,
            fingerprint(
                "voucher-reservation-release",
                "campaignId" to command.campaignId,
                "reservationId" to command.reservationId,
                "canonicalUser" to command.canonicalUser,
                "expectedRevision" to command.expectedRevision,
            ),
            command.reservationId,
            LIFECYCLE_HTTP_OK,
            LifecycleLane.FOREGROUND,
        ) { connection, _ ->
            val keyVersion = repository.userIdentityKeyVersion(command.tenantId, command.campaignId)
                ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            val digest = digests.userIdentity(command.tenantId, command.campaignId, command.canonicalUser, keyVersion)
            val chain = repository.lockReservationChain(
                command.tenantId, command.reservationId, digest.copyBytes(),
            ) ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            requireCampaignUsable(chain.campaign.state)
            requireBatchUsable(chain.batch.state)
            if (chain.reservation.revision != command.expectedRevision) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.reservation.state != ReservationState.ACTIVE.name) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.reservation.expiresAt <= repository.transactionTime()) fail(VoucherPoolErrorCode.RESERVATION_EXPIRED)
            val released = repository.releaseReservation(chain)
            repository.appendLifecycleAudit(
                released.tenantId,
                released.campaignId,
                "RESERVATION",
                released.reservationId,
                released.revision,
                released.policyVersion,
                "RELEASED",
                "CUSTOMER",
            )
            released.snapshot()
        }
}

internal enum class LifecycleLane { FOREGROUND, OPERATOR }

internal class LifecycleMutationExecutor(
    private val executor: VoucherPoolJdbcExecutor,
    private val idempotency: VoucherPoolIdempotencyRepository,
) {
    fun <T> execute(
        tenantId: String,
        operation: String,
        rawKey: String,
        fingerprint: CommandFingerprint,
        effectId: UUID,
        successStatus: Int,
        lane: LifecycleLane,
        effect: (Connection, IdempotencyOwner) -> T,
    ): MutationResult<T> {
        val scope = CommandScope(tenantId, operation)
        return when (val decision = transaction(lane) { idempotency.acquire(scope, rawKey, fingerprint) }) {
            is IdempotencyDecision.Execute -> executeOwned(decision.owner, effectId, successStatus, lane, effect)
            is IdempotencyDecision.Replay -> MutationResult.Replay(decision.descriptor)
            is IdempotencyDecision.Expired -> MutationResult.Expired(decision.effectId, decision.terminalCode)
            is IdempotencyDecision.InProgress -> fail(VoucherPoolErrorCode.COMMAND_IN_PROGRESS)
            IdempotencyDecision.FingerprintConflict -> fail(VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT)
        }
    }

    private fun <T> executeOwned(
        owner: IdempotencyOwner,
        effectId: UUID,
        successStatus: Int,
        lane: LifecycleLane,
        effect: (Connection, IdempotencyOwner) -> T,
    ): MutationResult<T> {
        val outcome = try {
            transaction(lane) {
                idempotency.lockOwnerForExecution(owner)
                try {
                    val applied = effect(currentConnection(), owner)
                    val descriptor = descriptor(applied, effectId, successStatus)
                    idempotency.finalize(owner, descriptor, EffectReference.effect(effectId))
                    OwnedLifecycleOutcome.Applied(applied)
                } catch (failure: CommitLifecycleFailure) {
                    idempotency.releaseRetryable(owner)
                    OwnedLifecycleOutcome.Failed(failure.lifecycleFailure)
                }
            }
        } catch (failure: VoucherPoolLifecycleException) {
            return handleLifecycleFailure(owner, lane, failure)
        } catch (failure: RuntimeException) {
            releaseRetryable(owner, lane)
            throw failure
        }
        return when (outcome) {
            is OwnedLifecycleOutcome.Applied -> MutationResult.Applied(outcome.value)
            is OwnedLifecycleOutcome.Failed -> throw outcome.failure
        }
    }

    private fun <T> handleLifecycleFailure(
        owner: IdempotencyOwner,
        lane: LifecycleLane,
        failure: VoucherPoolLifecycleException,
    ): MutationResult<T> {
        val semantics = VoucherPoolErrorCatalog[failure.code]
        when (semantics.descriptorAction) {
            DescriptorAction.STORE -> transaction(lane) {
                idempotency.lockOwnerForExecution(owner)
                idempotency.finalize(
                    owner,
                    SafeResponseDescriptor.terminal(semantics.httpStatus, failure.code),
                    EffectReference.terminal(failure.code),
                )
            }
            DescriptorAction.RELEASE, DescriptorAction.NONE -> releaseRetryable(owner, lane)
            DescriptorAction.STORE_SAFE -> {
                val descriptor = SafeResponseDescriptor.success(
                    semantics.httpStatus,
                    failure.code.name,
                    checkNotNull(failure.safeEffectId),
                    checkNotNull(failure.safeRevision),
                )
                transaction(lane) {
                    idempotency.lockOwnerForExecution(owner)
                    idempotency.finalize(
                        owner,
                        descriptor,
                        EffectReference.effect(checkNotNull(failure.safeEffectId)),
                    )
                }
                return MutationResult.Replay(descriptor)
            }
        }
        throw failure
    }

    private fun descriptor(value: Any?, effectId: UUID, status: Int): SafeResponseDescriptor = when (value) {
        is ReservationSnapshot -> SafeResponseDescriptor.success(status, "RESERVATION_${value.state.name}", effectId, value.revision)
        is AllocationSnapshot -> SafeResponseDescriptor.success(status, "ALLOCATION_${value.state.name}", effectId, value.revision)
        is RevealResult -> SafeResponseDescriptor.success(status, value.outcome, effectId, value.revision)
        else -> error("unsupported lifecycle mutation result")
    }

    private fun releaseRetryable(owner: IdempotencyOwner, lane: LifecycleLane) {
        releaseRetryableOwner {
            transaction(lane) { idempotency.releaseRetryable(owner) }
        }
    }

    private fun <T> transaction(lane: LifecycleLane, block: () -> T): T = when (lane) {
        LifecycleLane.FOREGROUND -> executor.foregroundTransaction(block)
        LifecycleLane.OPERATOR -> executor.operatorTransaction(block)
    }

}

private sealed interface OwnedLifecycleOutcome<out T> {
    data class Applied<T>(val value: T) : OwnedLifecycleOutcome<T>
    data class Failed(val failure: VoucherPoolLifecycleException) : OwnedLifecycleOutcome<Nothing>
}

private class CommitLifecycleFailure(
    val lifecycleFailure: VoucherPoolLifecycleException,
) : RuntimeException(lifecycleFailure)

internal fun ReservationRecord.snapshot() = ReservationSnapshot(
    tenantId,
    reservationId,
    campaignId,
    batchId,
    entryId,
    ReservationState.valueOf(state),
    expiresAt,
    entitlementRootId,
    replacementOrdinal,
    policyVersion,
    revision,
)

internal fun requireActive(state: CampaignState) {
    when (state) {
        CampaignState.ACTIVE -> Unit
        CampaignState.PAUSED -> fail(VoucherPoolErrorCode.CAMPAIGN_PAUSED)
        CampaignState.REVOKING -> fail(VoucherPoolErrorCode.CAMPAIGN_REVOKING)
        CampaignState.REVOKED -> fail(VoucherPoolErrorCode.CAMPAIGN_REVOKED)
        CampaignState.DRAFT -> fail(VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE)
    }
}

internal fun requireCampaignUsable(state: CampaignState) = requireActive(state)

internal fun requireBatchUsable(state: BatchState) {
    when (state) {
        BatchState.ACTIVE -> Unit
        BatchState.PAUSED -> fail(VoucherPoolErrorCode.BATCH_PAUSED)
        BatchState.EXPIRING -> fail(VoucherPoolErrorCode.BATCH_EXPIRING)
        BatchState.REVOKED, BatchState.REVOKING -> fail(VoucherPoolErrorCode.BATCH_REVOKED)
        BatchState.EXPIRED -> fail(VoucherPoolErrorCode.BATCH_EXPIRED)
        BatchState.FAILED_RETRYABLE -> fail(VoucherPoolErrorCode.BATCH_FAILED_RETRYABLE)
        BatchState.FAILED_TERMINAL -> fail(VoucherPoolErrorCode.BATCH_FAILED_TERMINAL)
        BatchState.STAGING -> fail(VoucherPoolErrorCode.SCOPE_NOT_FOUND)
    }
}

internal fun fingerprint(operation: String, vararg fields: Pair<String, Any?>): CommandFingerprint =
    VoucherPoolFingerprint.command(operation, fields.associate { it.first to it.second.toString() })

internal fun currentConnection(): Connection =
    checkNotNull(TransactionManager.currentOrNull()?.connection?.connection as? Connection) {
        "voucher pool lifecycle requires an active JDBC transaction"
    }

internal fun fail(code: VoucherPoolErrorCode): Nothing = throw VoucherPoolLifecycleException(code)

internal fun failSafe(code: VoucherPoolErrorCode, effectId: UUID, revision: Long): Nothing =
    throw VoucherPoolLifecycleException(code, effectId, revision)

internal fun failCommitted(code: VoucherPoolErrorCode): Nothing =
    throw CommitLifecycleFailure(VoucherPoolLifecycleException(code))

internal fun VoucherPoolRepository.appendLifecycleAudit(
    tenantId: String,
    campaignId: UUID,
    aggregateType: String,
    aggregateId: UUID,
    revision: Long,
    policyVersion: Long,
    reasonCode: String,
    actorType: String,
) = appendAudit(
    VoucherPoolAuditRecord(
        tenantId = tenantId,
        campaignId = campaignId,
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        revision = revision,
        policyVersion = policyVersion,
        actorType = actorType,
        reasonCode = reasonCode,
    ),
)

internal const val LIFECYCLE_HTTP_OK = 200
internal const val LIFECYCLE_HTTP_CREATED = 201
