package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.domain.ReviewKind
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRecord
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
import io.bluetape4k.workshop.commerce.voucher.security.VoucherGenerationInput
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal enum class RiskSignal {
    CLEAR,
    REVIEW,
}

internal enum class VoucherCommandFailure {
    CAMPAIGN_NOT_FOUND,
    CLAIM_NOT_FOUND,
    REVIEW_NOT_FOUND,
    CAMPAIGN_PAUSED,
    CAMPAIGN_NOT_ACTIVE,
    CAMPAIGN_NOT_STARTED,
    CAMPAIGN_ENDED,
    CAPACITY_EXHAUSTED,
    PER_USER_LIMIT_REACHED,
    INVALID_CODE,
    VOUCHER_EXPIRED,
    ALREADY_REDEEMED,
    STALE_REVISION,
    CONCURRENT_MODIFICATION,
    REPLAY_KEY_UNAVAILABLE,
}

internal class VoucherCommandException(
    val code: VoucherCommandFailure,
) : RuntimeException(code.name)

internal data class AllocateVoucherCommand(
    val tenantId: String,
    val campaignId: UUID,
    val userRef: String,
    val riskSignal: RiskSignal = RiskSignal.CLEAR,
)

internal data class AllocationResult(
    val claim: ClaimSnapshot,
    val allocationId: UUID,
    val oneTimeCode: String?,
    val reviewId: Long?,
)

/** Creates an allocated or review-required claim under the canonical campaign row lock. */
@Service
internal class AllocationService(
    private val transactions: VoucherTransactionRunner,
    private val campaigns: CampaignRepository,
    private val claims: ClaimRepository,
    private val reviews: ReviewRepository,
    private val audits: AuditRepository,
    private val codes: VoucherCodeService,
    private val clock: Clock,
) {
    fun allocate(command: AllocateVoucherCommand): AllocationResult {
        validate(command)
        val claimId = Uuid.V7.nextId()
        val allocationId = Uuid.V7.nextId()
        val issued = codes.issue(VoucherGenerationInput(command.tenantId, command.campaignId, allocationId))
        val now = Instant.now(clock)
        val userDigest = digestHex(USER_DIGEST_DOMAIN, command.tenantId, command.userRef)

        return transactions.foregroundTransaction {
            val campaign =
                campaigns.findPublicForUpdate(command.tenantId, command.campaignId)
                    ?: fail(VoucherCommandFailure.CAMPAIGN_NOT_FOUND)
            requireAllocatable(campaign, now)
            if (claims.countForUser(command.tenantId, command.campaignId, userDigest) >= campaign.perUserLimit) {
                fail(VoucherCommandFailure.PER_USER_LIMIT_REACHED)
            }

            val reviewRequired = command.riskSignal == RiskSignal.REVIEW
            if (!reviewRequired && campaign.allocatedCount >= campaign.capacity) {
                fail(VoucherCommandFailure.CAPACITY_EXHAUSTED)
            }
            if (!reviewRequired && !campaigns.tryReserve(campaign.tenantId, campaign.id, campaign.revision)) {
                fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            }

            val claim =
                claims.insert(
                    ClaimRecord(
                        id = 0,
                        tenantId = command.tenantId,
                        campaignRowId = campaign.id,
                        campaignId = campaign.campaignId,
                        claimId = claimId,
                        allocationId = allocationId,
                        userDigest = userDigest,
                        state = if (reviewRequired) ClaimState.REVIEW_REQUIRED else ClaimState.ALLOCATED,
                        reviewKind = if (reviewRequired) ReviewKind.ALLOCATION else null,
                        pendingFromState = if (reviewRequired) ClaimState.ELIGIBLE else null,
                        capacityReserved = !reviewRequired,
                        allocationPolicyVersion = campaign.policyVersion,
                        codeVerifier = issued.verifier.copyOf(),
                        generationKeyVersion = issued.generationKeyVersion,
                        verificationKeyVersion = issued.verificationKeyVersion,
                        expiresAt = now.plusSeconds(campaign.redemptionTtlSeconds),
                        redemptionReferenceDigest = null,
                        revision = 0,
                    ),
                )
            val review = if (reviewRequired) openReview(claim) else null
            audits.append(claim.audit(if (reviewRequired) "ALLOCATION_REVIEW_REQUIRED" else "ALLOCATED"))
            log.debug {
                "voucher_allocation_completed campaignId=${campaign.campaignId} claimId=${claim.claimId} " +
                    "state=${claim.state}"
            }
            AllocationResult(
                claim = claim.toSnapshot(),
                allocationId = allocationId,
                oneTimeCode = issued.code.takeUnless { reviewRequired },
                reviewId = review?.id,
            )
        }
    }

    private fun openReview(claim: ClaimRecord): ReviewRecord =
        reviews.insert(
            ReviewRecord(
                id = 0,
                tenantId = claim.tenantId,
                campaignId = claim.campaignId,
                claimRowId = claim.id,
                claimId = claim.claimId,
                kind = ReviewKind.ALLOCATION,
                status = ReviewStatus.OPEN,
                reasonCode = "RISK_REVIEW",
                signalSummary = "risk=review",
                reviewerActorDigest = null,
                expectedClaimRevision = claim.revision,
                revision = 0,
            ),
        )

    private fun validate(command: AllocateVoucherCommand) {
        require(command.tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(command.userRef.length in 1..128) { "userRef must contain 1..128 characters" }
    }

    companion object : KLogging() {
        private const val USER_DIGEST_DOMAIN = "voucher-user-v1"
    }
}

internal fun requireCampaignActive(
    campaign: CampaignRecord,
    now: Instant,
) {
    when (campaign.state) {
        CampaignState.PAUSED -> fail(VoucherCommandFailure.CAMPAIGN_PAUSED)
        CampaignState.ENDED -> fail(VoucherCommandFailure.CAMPAIGN_ENDED)
        CampaignState.DRAFT -> fail(VoucherCommandFailure.CAMPAIGN_NOT_ACTIVE)
        CampaignState.ACTIVE -> Unit
    }
    if (now.isBefore(campaign.startsAt)) fail(VoucherCommandFailure.CAMPAIGN_NOT_STARTED)
    if (!now.isBefore(campaign.endsAt)) fail(VoucherCommandFailure.CAMPAIGN_ENDED)
}

private fun requireAllocatable(
    campaign: CampaignRecord,
    now: Instant,
) = requireCampaignActive(campaign, now)

internal fun CampaignRecord.toSnapshot(): CampaignSnapshot =
    CampaignSnapshot(
        tenantId,
        campaignId,
        state,
        startsAt,
        endsAt,
        capacity,
        allocatedCount,
        perUserLimit,
        redemptionTtlSeconds,
        policyVersion,
        revision,
    )

internal fun ClaimRecord.toSnapshot(): ClaimSnapshot =
    ClaimSnapshot(
        tenantId,
        campaignId,
        claimId,
        state,
        reviewKind,
        pendingFromState,
        capacityReserved,
        allocationPolicyVersion,
        expiresAt,
        revision,
    )

internal fun ClaimRecord.audit(reasonCode: String): AuditRecord =
    AuditRecord(
        id = 0,
        tenantId = tenantId,
        campaignId = campaignId,
        aggregateType = "CLAIM",
        aggregateId = claimId,
        revision = revision,
        actorType = "USER",
        reasonCode = reasonCode,
        policyVersion = allocationPolicyVersion,
        correlationDigest = null,
    )

internal fun digestHex(
    domain: String,
    vararg values: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest((sequenceOf(domain) + values.asSequence()).joinToString("\u0000").toByteArray(UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun fail(code: VoucherCommandFailure): Nothing = throw VoucherCommandException(code)
