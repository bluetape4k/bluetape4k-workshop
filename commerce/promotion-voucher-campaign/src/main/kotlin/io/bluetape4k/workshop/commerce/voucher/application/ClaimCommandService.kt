package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.domain.ReviewKind
import io.bluetape4k.workshop.commerce.voucher.domain.TransitionOutcome
import io.bluetape4k.workshop.commerce.voucher.domain.VoucherPolicies
import io.bluetape4k.workshop.commerce.voucher.domain.VoucherPolicyException
import io.bluetape4k.workshop.commerce.voucher.domain.VoucherPolicyFailure
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewStatus
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class RedeemVoucherCommand(
    val tenantId: String,
    val code: String,
    val expectedRevision: Long,
    val redemptionReference: String,
    val riskSignal: RiskSignal = RiskSignal.CLEAR,
)

internal data class ClaimTransitionCommand(
    val tenantId: String,
    val campaignId: UUID,
    val claimId: UUID,
    val expectedRevision: Long,
)

/** Applies redemption and terminal transitions under campaign -> claim lock order. */
@Service
internal class ClaimCommandService(
    private val transactions: VoucherTransactionRunner,
    private val campaigns: CampaignRepository,
    private val claims: ClaimRepository,
    private val reviews: ReviewRepository,
    private val audits: AuditRepository,
    private val codes: VoucherCodeService,
    private val clock: Clock,
) {
    fun redeem(command: RedeemVoucherCommand): ClaimSnapshot {
        require(command.redemptionReference.length in 1..128) {
            "redemptionReference must contain 1..128 characters"
        }
        val lookup = codes.lookup(command.code) ?: fail(VoucherCommandFailure.INVALID_CODE)
        val now = Instant.now(clock)
        return transactions.foregroundTransaction {
            val candidate =
                claims.findByVerifier(command.tenantId, lookup.verifier)
                    ?: fail(VoucherCommandFailure.INVALID_CODE)
            val campaign =
                campaigns.findPublicForUpdate(command.tenantId, candidate.campaignId)
                    ?: fail(VoucherCommandFailure.CAMPAIGN_NOT_FOUND)
            requireCampaignRedeemable(campaign)
            val claim =
                claims.findPublicForUpdate(command.tenantId, candidate.claimId)
                    ?: fail(VoucherCommandFailure.CLAIM_NOT_FOUND)
            if (!codes.verify(command.code, checkNotNull(claim.codeVerifier), checkNotNull(claim.verificationKeyVersion))) {
                fail(VoucherCommandFailure.INVALID_CODE)
            }
            if (claim.state == ClaimState.REDEEMED) fail(VoucherCommandFailure.ALREADY_REDEEMED)
            if (claim.revision != command.expectedRevision) fail(VoucherCommandFailure.STALE_REVISION)

            val redemption = policy { VoucherPolicies.redeem(claim.toSnapshot(), command.expectedRevision, now) }
            val transitioned =
                if (command.riskSignal == RiskSignal.REVIEW) {
                    val snapshot =
                        redemption.claim.copy(
                            state = ClaimState.REVIEW_REQUIRED,
                            reviewKind = ReviewKind.REDEMPTION,
                            pendingFromState = ClaimState.ALLOCATED,
                        )
                    TransitionOutcome(snapshot, capacityDelta = 0)
                } else {
                    redemption
                }
            val updated =
                claim.apply(transitioned.claim).copy(
                    redemptionReferenceDigest =
                        digestHex(REDEMPTION_REFERENCE_DOMAIN, command.tenantId, command.redemptionReference),
                )
            if (!claims.transition(updated, claim.revision)) fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            if (updated.state == ClaimState.REVIEW_REQUIRED) openRedemptionReview(updated)
            audits.append(updated.audit(if (updated.state == ClaimState.REVIEW_REQUIRED) "REDEMPTION_REVIEW_REQUIRED" else "REDEEMED"))
            log.debug { "voucher_claim_redeemed claimId=${updated.claimId} state=${updated.state}" }
            updated.toSnapshot()
        }
    }

    fun release(command: ClaimTransitionCommand): ClaimSnapshot =
        terminate(command, "RELEASED") { claim, expected, _ -> VoucherPolicies.release(claim, expected) }

    fun revoke(command: ClaimTransitionCommand): ClaimSnapshot =
        terminate(command, "REVOKED") { claim, expected, _ -> VoucherPolicies.revoke(claim, expected) }

    fun expire(command: ClaimTransitionCommand): ClaimSnapshot =
        terminate(command, "EXPIRED") { claim, expected, now -> VoucherPolicies.expire(claim, expected, now) }

    private fun terminate(
        command: ClaimTransitionCommand,
        reasonCode: String,
        transition: (ClaimSnapshot, Long, Instant) -> TransitionOutcome,
    ): ClaimSnapshot =
        transactions.foregroundTransaction {
            val campaign =
                campaigns.findPublicForUpdate(command.tenantId, command.campaignId)
                    ?: fail(VoucherCommandFailure.CAMPAIGN_NOT_FOUND)
            val claim =
                claims.findPublicForUpdate(command.tenantId, command.claimId)
                    ?: fail(VoucherCommandFailure.CLAIM_NOT_FOUND)
            if (claim.campaignId != campaign.campaignId) fail(VoucherCommandFailure.CLAIM_NOT_FOUND)
            if (claim.revision != command.expectedRevision) fail(VoucherCommandFailure.STALE_REVISION)
            val outcome = policy { transition(claim.toSnapshot(), command.expectedRevision, Instant.now(clock)) }
            if (outcome.capacityDelta == -1 && !campaigns.tryRelease(campaign.tenantId, campaign.id, campaign.revision)) {
                fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            }
            val updated = claim.apply(outcome.claim)
            if (!claims.transition(updated, claim.revision)) fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            audits.append(updated.audit(reasonCode))
            log.debug { "voucher_claim_terminated claimId=${updated.claimId} state=${updated.state}" }
            updated.toSnapshot()
        }

    private fun openRedemptionReview(claim: ClaimRecord) {
        reviews.insert(
            ReviewRecord(
                id = 0,
                tenantId = claim.tenantId,
                campaignId = claim.campaignId,
                claimRowId = claim.id,
                claimId = claim.claimId,
                kind = ReviewKind.REDEMPTION,
                status = ReviewStatus.OPEN,
                reasonCode = "RISK_REVIEW",
                signalSummary = "risk=review",
                reviewerActorDigest = null,
                expectedClaimRevision = claim.revision,
                revision = 0,
            ),
        )
    }

    companion object : KLogging() {
        private const val REDEMPTION_REFERENCE_DOMAIN = "voucher-redemption-reference-v1"
    }
}

internal fun ClaimRecord.apply(snapshot: ClaimSnapshot): ClaimRecord =
    copy(
        state = snapshot.state,
        reviewKind = snapshot.reviewKind,
        pendingFromState = snapshot.pendingFromState,
        capacityReserved = snapshot.capacityReserved,
        expiresAt = snapshot.expiresAt,
        revision = snapshot.revision,
    )

internal inline fun <T> policy(block: () -> T): T =
    try {
        block()
    } catch (failure: VoucherPolicyException) {
        when (failure.code) {
            VoucherPolicyFailure.STALE_REVISION -> fail(VoucherCommandFailure.STALE_REVISION)
            VoucherPolicyFailure.VOUCHER_EXPIRED -> fail(VoucherCommandFailure.VOUCHER_EXPIRED)
            else -> fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
        }
    }

internal fun requireCampaignRedeemable(campaign: CampaignRecord) {
    when (campaign.state) {
        CampaignState.DRAFT -> fail(VoucherCommandFailure.CAMPAIGN_NOT_ACTIVE)
        CampaignState.PAUSED -> fail(VoucherCommandFailure.CAMPAIGN_PAUSED)
        CampaignState.ACTIVE, CampaignState.ENDED -> Unit
    }
}
