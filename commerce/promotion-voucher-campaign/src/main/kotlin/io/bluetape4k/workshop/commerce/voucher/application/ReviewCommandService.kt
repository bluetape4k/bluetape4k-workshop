package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ReviewKind
import io.bluetape4k.workshop.commerce.voucher.domain.VoucherPolicies
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewStatus
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeService
import io.bluetape4k.workshop.commerce.voucher.security.VoucherGenerationInput
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class ReviewDecisionCommand(
    val tenantId: String,
    val campaignId: UUID,
    val claimId: UUID,
    val reviewId: Long,
    val expectedReviewRevision: Long,
    val expectedClaimRevision: Long,
    val reviewerActorDigest: String = "0".repeat(64),
)

internal data class ReviewDecisionResult(
    val claim: ClaimSnapshot,
    val oneTimeCode: String?,
)

/** Applies review decisions under campaign -> claim -> review lock order. */
@Service
internal class ReviewCommandService(
    private val transactions: VoucherTransactionRunner,
    private val campaigns: CampaignRepository,
    private val claims: ClaimRepository,
    private val reviews: ReviewRepository,
    private val audits: AuditRepository,
    private val codes: VoucherCodeService,
    private val clock: Clock,
) {
    fun approve(command: ReviewDecisionCommand): ReviewDecisionResult = decide(command, approved = true)

    fun reject(command: ReviewDecisionCommand): ReviewDecisionResult = decide(command, approved = false)

    private fun decide(
        command: ReviewDecisionCommand,
        approved: Boolean,
    ): ReviewDecisionResult {
        require(SHA_256_HEX.matches(command.reviewerActorDigest)) {
            "reviewerActorDigest must be a lowercase SHA-256 hex digest"
        }
        return transactions.foregroundTransaction {
            val campaign =
                campaigns.findPublicForUpdate(command.tenantId, command.campaignId)
                    ?: fail(VoucherCommandFailure.CAMPAIGN_NOT_FOUND)
            val claim =
                claims.findPublicForUpdate(command.tenantId, command.claimId)
                    ?: fail(VoucherCommandFailure.CLAIM_NOT_FOUND)
            val review =
                reviews.findOpenForUpdate(command.tenantId, command.claimId, command.reviewId)
                    ?: fail(VoucherCommandFailure.REVIEW_NOT_FOUND)
            if (claim.campaignId != campaign.campaignId || review.campaignId != campaign.campaignId) {
                fail(VoucherCommandFailure.REVIEW_NOT_FOUND)
            }
            if (
                claim.revision != command.expectedClaimRevision ||
                review.revision != command.expectedReviewRevision ||
                review.expectedClaimRevision != command.expectedClaimRevision
            ) {
                fail(VoucherCommandFailure.STALE_REVISION)
            }

            val now = Instant.now(clock)
            if (approved && review.kind == ReviewKind.REDEMPTION) {
                requireCampaignRedeemable(campaign)
                if (claim.expiresAt?.let { !now.isBefore(it) } != false) {
                    fail(VoucherCommandFailure.VOUCHER_EXPIRED)
                }
            }

            val outcome =
                policy {
                    if (approved) {
                        VoucherPolicies.approveReview(claim.toSnapshot(), command.expectedClaimRevision)
                    } else {
                        VoucherPolicies.rejectReview(claim.toSnapshot(), command.expectedClaimRevision)
                    }
                }
            if (outcome.capacityDelta == 1) {
                requireCampaignActive(campaign, now)
                if (campaign.allocatedCount >= campaign.capacity) fail(VoucherCommandFailure.CAPACITY_EXHAUSTED)
                if (!campaigns.tryReserve(campaign.tenantId, campaign.id, campaign.revision)) {
                    fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
                }
            }
            val updated = claim.apply(outcome.claim)
            if (!claims.transition(updated, claim.revision)) fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            val status = if (approved) ReviewStatus.APPROVED else ReviewStatus.REJECTED
            if (!reviews.decide(review, status, command.reviewerActorDigest, command.expectedReviewRevision)) {
                fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            }
            audits.append(updated.audit(if (approved) "REVIEW_APPROVED" else "REVIEW_REJECTED"))

            val code = if (approved && review.kind == ReviewKind.ALLOCATION) reconstruct(updated) else null
            log.debug { "voucher_review_decided reviewId=${review.id} claimId=${claim.claimId} status=$status" }
            ReviewDecisionResult(updated.toSnapshot(), code)
        }
    }

    private fun reconstruct(claim: ClaimRecord): String {
        val issued =
            codes.reconstruct(
                VoucherGenerationInput(claim.tenantId, claim.campaignId, claim.allocationId),
                generationKeyVersion = claim.generationKeyVersion ?: fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE),
                verificationKeyVersion =
                    claim.verificationKeyVersion ?: fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE),
            ) ?: fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE)
        if (!MessageDigest.isEqual(issued.verifier, claim.codeVerifier)) {
            fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE)
        }
        return issued.code
    }

    companion object : KLogging() {
        private val SHA_256_HEX = Regex("[0-9a-f]{64}")
    }
}
