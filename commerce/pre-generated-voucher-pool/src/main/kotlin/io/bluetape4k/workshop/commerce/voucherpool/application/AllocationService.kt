@file:Suppress(
    "LongMethod", // The allocation transaction keeps the canonical lock and crypto sequence visible in one place.
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
)

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.AllocationRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.ReservationRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.EntryIdentity
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoStorage
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoStorageOutcome
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal data class AllocateVoucherCommand(
    val tenantId: String,
    val campaignId: UUID,
    val reservationId: UUID,
    val canonicalUser: String,
    val expectedRevision: Long,
    val idempotencyKey: String,
    val allocationId: UUID = UUID.randomUUID(),
)

internal data class RevealVoucherCommand(
    val tenantId: String,
    val campaignId: UUID,
    val allocationId: UUID,
    val canonicalUser: String,
    val expectedRevision: Long,
    val idempotencyKey: String,
)

internal data class ReplaceLostRevealCommand(
    val tenantId: String,
    val campaignId: UUID,
    val allocationId: UUID,
    val canonicalUser: String,
    val expectedRevision: Long,
    val idempotencyKey: String,
    val reservationId: UUID = UUID.randomUUID(),
)

internal data class AllocationSnapshot(
    val tenantId: String,
    val allocationId: UUID,
    val reservationId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val sourceOrdinal: Long,
    val state: EntryState,
    val expiresAt: Instant,
    val entitlementRootId: UUID,
    val replacementOrdinal: Int,
    val policyVersion: Long,
    val revision: Long,
)

internal class RevealResult internal constructor(
    val allocationId: UUID,
    val outcome: String,
    val revision: Long,
) {
    @Volatile
    private var committedCode: CanonicalVoucherCode? = null

    val code: CanonicalVoucherCode? get() = committedCode

    internal fun deliverAfterCommit(code: CanonicalVoucherCode) {
        check(committedCode == null)
        committedCode = code
    }

    override fun toString(): String =
        "RevealResult(allocationId=$allocationId,outcome=$outcome,revision=$revision,code=[REDACTED])"
}

internal interface AllocationService {
    fun allocate(command: AllocateVoucherCommand): MutationResult<AllocationSnapshot>
    fun reveal(command: RevealVoucherCommand): MutationResult<RevealResult>
    fun replaceLostReveal(command: ReplaceLostRevealCommand): MutationResult<ReservationSnapshot>
}

internal class JdbcAllocationService(
    executor: VoucherPoolJdbcExecutor,
    private val repository: VoucherPoolRepository,
    idempotency: VoucherPoolIdempotencyRepository,
    private val digests: VoucherDigestService,
    private val cryptoStorage: VoucherCryptoStorage,
) : AllocationService {
    private val executor = executor
    private val mutations = LifecycleMutationExecutor(executor, idempotency)

    override fun allocate(command: AllocateVoucherCommand): MutationResult<AllocationSnapshot> =
        mutations.execute(
            command.tenantId,
            "voucher-allocate",
            command.idempotencyKey,
            fingerprint(
                "voucher-allocate",
                "campaignId" to command.campaignId,
                "reservationId" to command.reservationId,
                "canonicalUser" to command.canonicalUser,
                "expectedRevision" to command.expectedRevision,
            ),
            command.allocationId,
            LIFECYCLE_HTTP_OK,
            LifecycleLane.FOREGROUND,
        ) { connection, _ ->
            val userDigest = userIdentity(connection, command.tenantId, command.campaignId, command.canonicalUser)
            val chain = repository.lockReservationChain(
                connection, command.tenantId, command.reservationId, userDigest.copyBytes(),
            ) ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            requireCampaignUsable(chain.campaign.state)
            requireBatchUsable(chain.batch.state)
            if (chain.reservation.revision != command.expectedRevision) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.reservation.state != ReservationState.ACTIVE.name || chain.entry.state != EntryState.RESERVED) {
                fail(VoucherPoolErrorCode.STALE_REVISION)
            }
            requireCurrentPolicy(chain.reservation, chain.campaign.policyVersion)
            if (chain.entry.quarantinedAt != null) fail(VoucherPoolErrorCode.CIPHERTEXT_INVALID)
            val transactionTime = repository.transactionTime(connection)
            if (chain.reservation.expiresAt <= transactionTime) fail(VoucherPoolErrorCode.RESERVATION_EXPIRED)
            if (chain.batch.expiresAt?.let { it <= transactionTime } == true) fail(VoucherPoolErrorCode.BATCH_EXPIRED)
            val identity = EntryIdentity(
                chain.entry.tenantId,
                chain.entry.campaignId,
                chain.entry.batchId,
                chain.entry.entryId,
                chain.entry.sourceOrdinal,
            )
            val code = when (val decrypted = cryptoStorage.decryptRetained(connection, identity, chain.entry.revision)) {
                is VoucherCryptoStorageOutcome.Revealed -> decrypted.code
                is VoucherCryptoStorageOutcome.Quarantined -> failCommitted(VoucherPoolErrorCode.CIPHERTEXT_INVALID)
            }
            val verification = digests.verification(command.tenantId, command.campaignId, command.allocationId, code)
            check(verification.purpose == DigestPurpose.VERIFICATION)
            val entitlementRoot = chain.reservation.entitlementRootId ?: command.allocationId
            val policyExpiry = transactionTime.plusSeconds(chain.campaign.allocationTtlSeconds)
            val allocation = AllocationRecord(
                command.tenantId,
                command.allocationId,
                command.reservationId,
                command.campaignId,
                chain.entry.batchId,
                chain.entry.entryId,
                DigestValue.of(userDigest.copyBytes()),
                entitlementRoot,
                chain.reservation.replacementOrdinal,
                chain.batch.expiresAt?.let { minOf(it, policyExpiry) } ?: policyExpiry,
                chain.reservation.policyVersion,
                0,
            )
            val allocated = repository.allocateReservation(
                connection,
                chain,
                allocation,
                verification.copyBytes(),
                verification.keyVersion,
            )
            repository.appendLifecycleAudit(
                connection,
                allocated.allocation.tenantId,
                allocated.allocation.campaignId,
                "ALLOCATION",
                allocated.allocation.allocationId,
                allocated.allocation.revision,
                allocated.allocation.policyVersion,
                "ALLOCATED",
                "CUSTOMER",
            )
            allocated.snapshot()
        }

    override fun reveal(command: RevealVoucherCommand): MutationResult<RevealResult> {
        val result = mutations.execute(
            command.tenantId,
            "voucher-reveal",
            command.idempotencyKey,
            fingerprint(
                "voucher-reveal",
                "campaignId" to command.campaignId,
                "allocationId" to command.allocationId,
                "canonicalUser" to command.canonicalUser,
                "expectedRevision" to command.expectedRevision,
            ),
            command.allocationId,
            LIFECYCLE_HTTP_OK,
            LifecycleLane.FOREGROUND,
        ) { connection, _ ->
            val digest = userIdentity(connection, command.tenantId, command.campaignId, command.canonicalUser)
            val chain = repository.lockAllocationChain(connection, command.tenantId, command.allocationId, digest.copyBytes())
                ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            requireCampaignUsable(chain.campaign.state)
            requireBatchUsable(chain.batch.state)
            if (chain.entry.state != EntryState.ALLOCATED) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.entry.quarantinedAt != null) fail(VoucherPoolErrorCode.CIPHERTEXT_INVALID)
            if (chain.allocation.expiresAt <= repository.transactionTime(connection)) fail(VoucherPoolErrorCode.ALLOCATION_EXPIRED)
            if (chain.entry.revealedAt != null) {
                return@execute RevealResult(
                    command.allocationId,
                    VoucherPoolErrorCode.ALREADY_REVEALED.name,
                    chain.allocation.revision,
                )
            }
            if (chain.allocation.revision != command.expectedRevision) fail(VoucherPoolErrorCode.STALE_REVISION)
            val outcome = cryptoStorage.decryptAndErase(
                connection,
                EntryIdentity(
                    chain.entry.tenantId,
                    chain.entry.campaignId,
                    chain.entry.batchId,
                    chain.entry.entryId,
                    chain.entry.sourceOrdinal,
                ),
                chain.entry.revision,
            )
            when (outcome) {
                is VoucherCryptoStorageOutcome.Quarantined -> failCommitted(VoucherPoolErrorCode.CIPHERTEXT_INVALID)
                is VoucherCryptoStorageOutcome.Revealed -> {
                    val advancedAllocation = repository.advanceAllocationRevision(connection, chain.allocation)
                    val revealed = RevealResult(
                        command.allocationId,
                        "VOUCHER_REVEALED",
                        advancedAllocation.revision,
                    )
                    repository.appendLifecycleAudit(
                        connection,
                        advancedAllocation.tenantId,
                        advancedAllocation.campaignId,
                        "ALLOCATION",
                        advancedAllocation.allocationId,
                        revealed.revision,
                        advancedAllocation.policyVersion,
                        "REVEALED",
                        "CUSTOMER",
                    )
                    executor.afterCommit { revealed.deliverAfterCommit(outcome.code) }
                    revealed
                }
            }
        }
        return when (result) {
            is MutationResult.Replay -> if (result.descriptor.terminalCode == null) {
                MutationResult.Replay(
                    SafeResponseDescriptor.success(
                        LIFECYCLE_HTTP_OK,
                        VoucherPoolErrorCode.ALREADY_REVEALED.name,
                        checkNotNull(result.descriptor.effectId),
                        checkNotNull(result.descriptor.revision),
                    ),
                )
            } else {
                result
            }
            else -> result
        }
    }

    override fun replaceLostReveal(command: ReplaceLostRevealCommand): MutationResult<ReservationSnapshot> =
        mutations.execute(
            command.tenantId,
            "voucher-replace-lost-reveal",
            command.idempotencyKey,
            fingerprint(
                "voucher-replace-lost-reveal",
                "campaignId" to command.campaignId,
                "allocationId" to command.allocationId,
                "canonicalUser" to command.canonicalUser,
                "expectedRevision" to command.expectedRevision,
            ),
            command.reservationId,
            LIFECYCLE_HTTP_CREATED,
            LifecycleLane.FOREGROUND,
        ) { connection, owner ->
            val digest = userIdentity(connection, command.tenantId, command.campaignId, command.canonicalUser)
            val chain = repository.lockReplacementChain(
                connection, command.tenantId, command.allocationId, digest.copyBytes(),
            ) ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            val original = chain.original
            if (original.allocation.replacementOrdinal != 0 || original.entry.state != EntryState.ALLOCATED) {
                failSafe(
                    VoucherPoolErrorCode.ALREADY_REVEALED,
                    original.allocation.allocationId,
                    original.allocation.revision,
                )
    }

            if (original.allocation.revision != command.expectedRevision) fail(VoucherPoolErrorCode.STALE_REVISION)
            requireCampaignUsable(original.campaign.state)
            requireBatchUsable(original.batch.state)
            if (original.campaign.replacementAllowance < 1 || original.entry.revealedAt == null) {
                failSafe(
                    VoucherPoolErrorCode.ALREADY_REVEALED,
                    original.allocation.allocationId,
                    original.allocation.revision,
                )
            }
            val candidate = chain.candidate ?: fail(
                if (chain.availableCandidateExists) VoucherPoolErrorCode.POOL_BUSY else VoucherPoolErrorCode.POOL_EXHAUSTED,
            )
            val replacement = repository.replaceLostReveal(
                connection,
                chain,
                ReservationRecord(
                    command.tenantId,
                    command.reservationId,
                    original.campaign.campaignId,
                    candidate.batchId,
                    candidate.entryId,
                    DigestValue.of(digest.copyBytes()),
                    DigestValue.of(owner.copyScopedKeyDigest()),
                    ReservationState.ACTIVE.name,
                    repository.transactionTime(connection).plusSeconds(original.campaign.reservationTtlSeconds),
                    original.allocation.entitlementRootId,
                    1,
                    original.allocation.policyVersion,
                    0,
                ),
            )
            repository.appendLifecycleAudit(
                connection,
                original.allocation.tenantId,
                original.allocation.campaignId,
                "ALLOCATION",
                original.allocation.allocationId,
                original.allocation.revision + 1,
                original.allocation.policyVersion,
                "LOST_REVEAL_REVOKED",
                "CUSTOMER",
            )
            repository.appendLifecycleAudit(
                connection,
                replacement.tenantId,
                replacement.campaignId,
                "RESERVATION",
                replacement.reservationId,
                replacement.revision,
                replacement.policyVersion,
                "REPLACEMENT_RESERVED",
                "CUSTOMER",
            )
            replacement.snapshot()
        }

    private fun userIdentity(connection: Connection, tenantId: String, campaignId: UUID, canonicalUser: String) =
        digests.userIdentity(
            tenantId,
            campaignId,
            canonicalUser,
            repository.userIdentityKeyVersion(connection, tenantId, campaignId)
                ?: fail(VoucherPoolErrorCode.WRONG_OWNER),
        )
}

private fun requireCurrentPolicy(reservation: ReservationRecord, campaignPolicyVersion: Long) {
    if (reservation.policyVersion != campaignPolicyVersion) fail(VoucherPoolErrorCode.STALE_REVISION)
}

internal fun io.bluetape4k.workshop.commerce.voucherpool.persistence.LockedAllocationChain.snapshot() = AllocationSnapshot(
    allocation.tenantId,
    allocation.allocationId,
    allocation.reservationId,
    allocation.campaignId,
    allocation.batchId,
    allocation.entryId,
    entry.sourceOrdinal,
    entry.state,
    allocation.expiresAt,
    allocation.entitlementRootId,
    allocation.replacementOrdinal,
    allocation.policyVersion,
    allocation.revision,
)
