package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeService
import io.bluetape4k.workshop.commerce.voucher.security.VoucherGenerationInput
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.UUID

internal data class AcknowledgeVoucherCodeCommand(
    val tenantId: String,
    val campaignId: UUID,
    val claimId: UUID,
    val expectedRevision: Long,
)

internal data class AcknowledgeVoucherCodeResult(
    val claim: ClaimSnapshot,
    val code: String,
)

/** Delivers a review-approved code once, with same-key response-loss recovery delegated to idempotency. */
@Service
internal class VoucherCodeAcknowledgementService(
    private val transactions: VoucherTransactionRunner,
    private val campaigns: CampaignRepository,
    private val claims: ClaimRepository,
    private val audits: AuditRepository,
    private val codes: VoucherCodeService,
) {
    fun acknowledge(command: AcknowledgeVoucherCodeCommand): AcknowledgeVoucherCodeResult =
        transactions.foregroundTransaction {
            campaigns.findPublicForUpdate(command.tenantId, command.campaignId)
                ?: fail(VoucherCommandFailure.CAMPAIGN_NOT_FOUND)
            val claim = claims.findPublicForUpdate(command.tenantId, command.claimId)
                ?: fail(VoucherCommandFailure.CLAIM_NOT_FOUND)
            if (claim.campaignId != command.campaignId) fail(VoucherCommandFailure.CLAIM_NOT_FOUND)
            if (audits.hasReason(command.tenantId, claim.claimId, ACKNOWLEDGED_REASON)) {
                fail(VoucherCommandFailure.CODE_ALREADY_ACKNOWLEDGED)
            }
            if (claim.state != ClaimState.ALLOCATED ||
                !audits.hasReason(command.tenantId, claim.claimId, REVIEW_APPROVED_REASON)
            ) {
                fail(VoucherCommandFailure.CODE_ALREADY_ACKNOWLEDGED)
            }
            if (claim.revision != command.expectedRevision) fail(VoucherCommandFailure.STALE_REVISION)

            val updated = claim.copy(revision = claim.revision + 1)
            if (!claims.transition(updated, claim.revision)) fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            audits.append(updated.audit(ACKNOWLEDGED_REASON))
            val generationVersion = updated.generationKeyVersion ?: fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE)
            val verificationVersion = updated.verificationKeyVersion ?: fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE)
            val issued =
                codes.reconstruct(
                    VoucherGenerationInput(updated.tenantId, updated.campaignId, updated.allocationId),
                    generationVersion,
                    verificationVersion,
                ) ?: fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE)
            if (!MessageDigest.isEqual(issued.verifier, updated.codeVerifier)) {
                fail(VoucherCommandFailure.REPLAY_KEY_UNAVAILABLE)
            }
            log.info { "voucher_code_acknowledged claimId=${updated.claimId} revision=${updated.revision}" }
            AcknowledgeVoucherCodeResult(updated.toSnapshot(), issued.code)
        }

    companion object : KLogging() {
        private const val REVIEW_APPROVED_REASON = "REVIEW_APPROVED"
        private const val ACKNOWLEDGED_REASON = "CODE_ACKNOWLEDGED"
    }
}
