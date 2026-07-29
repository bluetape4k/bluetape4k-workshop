package io.bluetape4k.workshop.commerce.voucher.query

import io.bluetape4k.workshop.commerce.voucher.application.toSnapshot
import io.bluetape4k.workshop.commerce.voucher.application.voucherUserDigest
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewStatus
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeService
import io.bluetape4k.workshop.commerce.voucher.security.VoucherGenerationInput
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.UUID

internal data class ClaimReplayDescriptor(
    val claim: ClaimSnapshot,
    val allocationId: UUID,
    val generationKeyVersion: Int?,
    val verificationKeyVersion: Int?,
    val reviewId: Long?,
)

/** side-effect가 없는 tenant- 및 owner-scoped HTTP read model을 제공합니다. */
@Service
internal class VoucherQueryService(
    private val transactions: VoucherTransactionRunner,
    private val campaigns: CampaignRepository,
    private val claims: ClaimRepository,
    private val reviews: ReviewRepository,
    private val inbox: EventInboxRepository,
    private val codes: VoucherCodeService,
) {
    fun campaign(
        tenantId: String,
        campaignId: UUID,
    ): CampaignSnapshot? =
        transactions.foregroundTransaction { campaigns.findPublic(tenantId, campaignId)?.toSnapshot() }

    fun claimOwned(
        tenantId: String,
        principalRef: String,
        claimId: UUID,
    ): ClaimSnapshot? =
        transactions.foregroundTransaction {
            claims.findPublic(tenantId, claimId)
                ?.takeIf { it.userDigest == voucherUserDigest(tenantId, principalRef) }
                ?.toSnapshot()
        }

    fun claimForTenant(
        tenantId: String,
        claimId: UUID,
    ): ClaimSnapshot? = descriptor(tenantId, claimId)?.claim

    fun descriptor(
        tenantId: String,
        claimId: UUID,
    ): ClaimReplayDescriptor? =
        transactions.foregroundTransaction {
            val claim = claims.findPublic(tenantId, claimId) ?: return@foregroundTransaction null
            claim.descriptor(reviews.findOpen(tenantId, claimId))
        }

    fun reviews(
        tenantId: String,
        status: ReviewStatus,
        afterId: Long?,
        limit: Int,
    ): List<ReviewRecord> =
        transactions.foregroundTransaction { reviews.findPage(tenantId, status, afterId, limit) }

    fun reconciliationBacklog(
        tenantId: String,
        afterId: Long?,
        limit: Int,
    ): List<EventInboxRecord> =
        transactions.foregroundTransaction { inbox.findBacklogPage(tenantId, afterId, limit) }

    /** allocated claim에 대해서만 code를 재구성하고 persisted digest와 대조해 검증합니다. */
    fun activeCode(
        tenantId: String,
        claimId: UUID,
    ): String? =
        transactions.foregroundTransaction {
            val claim = claims.findPublic(tenantId, claimId) ?: return@foregroundTransaction null
            if (claim.state != ClaimState.ALLOCATED) return@foregroundTransaction null
            reconstruct(claim)
        }

    private fun ClaimRecord.descriptor(review: ReviewRecord?): ClaimReplayDescriptor =
        ClaimReplayDescriptor(
            claim = toSnapshot(),
            allocationId = allocationId,
            generationKeyVersion = generationKeyVersion,
            verificationKeyVersion = verificationKeyVersion,
            reviewId = review?.id,
        )

    private fun reconstruct(claim: ClaimRecord): String? {
        val generationVersion = claim.generationKeyVersion ?: return null
        val verificationVersion = claim.verificationKeyVersion ?: return null
        val verifier = claim.codeVerifier ?: return null
        val issued =
            codes.reconstruct(
                VoucherGenerationInput(claim.tenantId, claim.campaignId, claim.allocationId),
                generationVersion,
                verificationVersion,
            ) ?: return null
        return issued.code.takeIf { MessageDigest.isEqual(issued.verifier, verifier) }
    }
}
