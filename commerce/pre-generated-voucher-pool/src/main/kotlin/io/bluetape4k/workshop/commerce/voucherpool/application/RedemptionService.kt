@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.LockedAllocationChain
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigest
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import java.util.Base64
import java.util.UUID

internal data class RedeemVoucherCommand(
    val tenantId: String,
    val campaignId: UUID,
    val allocationId: UUID,
    val canonicalUser: String,
    val code: CanonicalVoucherCode,
    val expectedRevision: Long,
    val idempotencyKey: String,
)

internal data class RevokeAllocationCommand(
    val tenantId: String,
    val allocationId: UUID,
    val expectedRevision: Long,
    val idempotencyKey: String,
)

internal data class ReleaseAllocationCommand(
    val tenantId: String,
    val campaignId: UUID,
    val allocationId: UUID,
    val canonicalUser: String,
    val expectedRevision: Long,
    val idempotencyKey: String,
)

internal interface RedemptionService {
    fun redeem(command: RedeemVoucherCommand): MutationResult<AllocationSnapshot>
    fun release(command: ReleaseAllocationCommand): MutationResult<AllocationSnapshot>
    fun revoke(command: RevokeAllocationCommand): MutationResult<AllocationSnapshot>
}

internal class JdbcRedemptionService(
    executor: VoucherPoolJdbcExecutor,
    private val repository: VoucherPoolRepository,
    idempotency: VoucherPoolIdempotencyRepository,
    private val digests: VoucherDigestService,
) : RedemptionService {
    private val mutations = LifecycleMutationExecutor(executor, idempotency)

    override fun redeem(command: RedeemVoucherCommand): MutationResult<AllocationSnapshot> =
        mutations.execute(
            command.tenantId,
            "voucher-redeem",
            command.idempotencyKey,
            fingerprint(
                "voucher-redeem",
                "campaignId" to command.campaignId,
                "allocationId" to command.allocationId,
                "canonicalUser" to command.canonicalUser,
                "expectedRevision" to command.expectedRevision,
                "codeDigest" to Base64.getUrlEncoder().withoutPadding().encodeToString(
                    digests.stableDedup(command.tenantId, command.code).copyBytes(),
                ),
            ),
            command.allocationId,
            LIFECYCLE_HTTP_OK,
            LifecycleLane.FOREGROUND,
        ) { connection, _ ->
            val keyVersion = repository.userIdentityKeyVersion(connection, command.tenantId, command.campaignId)
                ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            val userDigest = digests.userIdentity(
                command.tenantId, command.campaignId, command.canonicalUser, keyVersion,
            )
            val chain = repository.lockAllocationChain(
                connection, command.tenantId, command.allocationId, userDigest.copyBytes(),
            ) ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            requireCampaignUsable(chain.campaign.state)
            requireBatchUsable(chain.batch.state)
            if (chain.allocation.revision != command.expectedRevision) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.entry.state != EntryState.ALLOCATED) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.entry.quarantinedAt != null) fail(VoucherPoolErrorCode.CIPHERTEXT_INVALID)
            val transactionTime = repository.transactionTime(connection)
            if (chain.allocation.expiresAt <= transactionTime) fail(VoucherPoolErrorCode.ALLOCATION_EXPIRED)
            if (chain.batch.expiresAt?.let { it <= transactionTime } == true) fail(VoucherPoolErrorCode.BATCH_EXPIRED)
            if (!matchesVerification(command, chain)) fail(VoucherPoolErrorCode.SCOPE_NOT_FOUND)
            if (chain.entry.revealedAt == null) fail(VoucherPoolErrorCode.STALE_REVISION)
            val redeemed = repository.transitionAllocationTerminal(connection, chain, EntryState.REDEEMED, "REDEEMED")
            repository.appendLifecycleAudit(
                connection,
                redeemed.allocation.tenantId,
                redeemed.allocation.campaignId,
                "ALLOCATION",
                redeemed.allocation.allocationId,
                redeemed.allocation.revision,
                redeemed.allocation.policyVersion,
                "REDEEMED",
                "CUSTOMER",
            )
            redeemed.snapshot()
        }

    private fun matchesVerification(command: RedeemVoucherCommand, chain: LockedAllocationChain): Boolean {
        val expected = VoucherDigest.of(
            DigestPurpose.VERIFICATION,
            chain.entry.verificationKeyVersion ?: fail(VoucherPoolErrorCode.CIPHERTEXT_INVALID),
            chain.entry.verificationDigest?.copyBytes() ?: fail(VoucherPoolErrorCode.CIPHERTEXT_INVALID),
        )
        return digests.matchesVerification(
            command.tenantId,
            chain.allocation.campaignId,
            command.allocationId,
            command.code,
            expected,
        )
    }

    override fun release(command: ReleaseAllocationCommand): MutationResult<AllocationSnapshot> =
        mutations.execute(
            command.tenantId,
            "voucher-allocation-release",
            command.idempotencyKey,
            fingerprint(
                "voucher-allocation-release",
                "campaignId" to command.campaignId,
                "allocationId" to command.allocationId,
                "canonicalUser" to command.canonicalUser,
                "expectedRevision" to command.expectedRevision,
            ),
            command.allocationId,
            LIFECYCLE_HTTP_OK,
            LifecycleLane.FOREGROUND,
        ) { connection, _ ->
            val keyVersion = repository.userIdentityKeyVersion(connection, command.tenantId, command.campaignId)
                ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            val userDigest = digests.userIdentity(
                command.tenantId, command.campaignId, command.canonicalUser, keyVersion,
            )
            val chain = repository.lockAllocationChain(
                connection,
                command.tenantId,
                command.allocationId,
                userDigest.copyBytes(),
            ) ?: fail(VoucherPoolErrorCode.WRONG_OWNER)
            if (chain.allocation.revision != command.expectedRevision) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.entry.state != EntryState.ALLOCATED) fail(VoucherPoolErrorCode.STALE_REVISION)
            val released = repository.transitionAllocationTerminal(
                connection,
                chain,
                EntryState.RELEASED,
                "CUSTOMER_RELEASED",
            )
            repository.appendLifecycleAudit(
                connection,
                released.allocation.tenantId,
                released.allocation.campaignId,
                "ALLOCATION",
                released.allocation.allocationId,
                released.allocation.revision,
                released.allocation.policyVersion,
                "CUSTOMER_RELEASED",
                "CUSTOMER",
            )
            released.snapshot()
        }

    override fun revoke(command: RevokeAllocationCommand): MutationResult<AllocationSnapshot> =
        mutations.execute(
            command.tenantId,
            "voucher-allocation-revoke",
            command.idempotencyKey,
            fingerprint(
                "voucher-allocation-revoke",
                "allocationId" to command.allocationId,
                "expectedRevision" to command.expectedRevision,
            ),
            command.allocationId,
            LIFECYCLE_HTTP_OK,
            LifecycleLane.OPERATOR,
        ) { connection, _ ->
            val chain = repository.lockAllocationChain(connection, command.tenantId, command.allocationId, null)
                ?: fail(VoucherPoolErrorCode.SCOPE_NOT_FOUND)
            if (
                chain.entry.state == EntryState.REDEEMED &&
                chain.allocation.revision - 1 == command.expectedRevision
            ) {
                val audited = repository.advanceAllocationRevision(connection, chain.allocation)
                repository.appendLifecycleAudit(
                    connection,
                    audited.tenantId,
                    audited.campaignId,
                    "ALLOCATION",
                    audited.allocationId,
                    audited.revision,
                    audited.policyVersion,
                    "REVOKE_RACE_LOST",
                    "OPERATOR",
                )
                failCommitted(VoucherPoolErrorCode.STALE_REVISION)
            }
            if (chain.allocation.revision != command.expectedRevision) fail(VoucherPoolErrorCode.STALE_REVISION)
            if (chain.entry.state != EntryState.ALLOCATED) fail(VoucherPoolErrorCode.STALE_REVISION)
            val revoked = repository.transitionAllocationTerminal(connection, chain, EntryState.REVOKED, "OPERATOR_REVOKED")
            repository.appendLifecycleAudit(
                connection,
                revoked.allocation.tenantId,
                revoked.allocation.campaignId,
                "ALLOCATION",
                revoked.allocation.allocationId,
                revoked.allocation.revision,
                revoked.allocation.policyVersion,
                "OPERATOR_REVOKED",
                "OPERATOR",
            )
            revoked.snapshot()
        }
}
