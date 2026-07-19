package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.VoucherPolicies
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class CampaignTransitionCommand(
    val tenantId: String,
    val campaignId: UUID,
    val expectedRevision: Long,
)

internal data class CampaignPolicyCommand(
    val tenantId: String,
    val campaignId: UUID,
    val expectedRevision: Long,
    val capacity: Int,
    val perUserLimit: Int,
    val redemptionTtlSeconds: Long,
)

/** Applies operator campaign commands while holding the same canonical campaign lock as claims. */
@Service
internal class CampaignCommandService(
    private val transactions: VoucherTransactionRunner,
    private val campaigns: CampaignRepository,
    private val audits: AuditRepository,
    private val clock: Clock,
) {
    fun activate(command: CampaignTransitionCommand): CampaignSnapshot =
        transition(command, "CAMPAIGN_ACTIVATED") { campaign ->
            VoucherPolicies.activateCampaign(campaign, command.expectedRevision, Instant.now(clock))
        }

    fun pause(command: CampaignTransitionCommand): CampaignSnapshot =
        transition(command, "CAMPAIGN_PAUSED") { campaign ->
            VoucherPolicies.pauseCampaign(campaign, command.expectedRevision)
        }

    fun end(command: CampaignTransitionCommand): CampaignSnapshot =
        transition(command, "CAMPAIGN_ENDED") { campaign ->
            VoucherPolicies.endCampaign(campaign, command.expectedRevision)
        }

    fun updatePolicy(command: CampaignPolicyCommand): CampaignSnapshot =
        transition(
            CampaignTransitionCommand(command.tenantId, command.campaignId, command.expectedRevision),
            "CAMPAIGN_POLICY_UPDATED",
        ) { campaign ->
            VoucherPolicies.updateCampaignPolicy(
                campaign,
                command.expectedRevision,
                command.capacity,
                command.perUserLimit,
                command.redemptionTtlSeconds,
            )
        }

    private fun transition(
        command: CampaignTransitionCommand,
        reasonCode: String,
        policyTransition: (CampaignSnapshot) -> CampaignSnapshot,
    ): CampaignSnapshot =
        transactions.foregroundTransaction {
            val persisted =
                campaigns.findPublicForUpdate(command.tenantId, command.campaignId)
                    ?: fail(VoucherCommandFailure.CAMPAIGN_NOT_FOUND)
            if (persisted.revision != command.expectedRevision) fail(VoucherCommandFailure.STALE_REVISION)
            val updated = persisted.apply(policy { policyTransition(persisted.toSnapshot()) })
            if (!campaigns.transition(updated, persisted.revision)) {
                fail(VoucherCommandFailure.CONCURRENT_MODIFICATION)
            }
            audits.append(updated.auditCampaign(reasonCode))
            log.debug { "voucher_campaign_transitioned campaignId=${updated.campaignId} state=${updated.state}" }
            updated.toSnapshot()
        }

    companion object : KLogging()
}

internal fun CampaignRecord.apply(snapshot: CampaignSnapshot): CampaignRecord =
    copy(
        state = snapshot.state,
        startsAt = snapshot.startsAt,
        endsAt = snapshot.endsAt,
        capacity = snapshot.capacity,
        allocatedCount = snapshot.allocatedCount,
        perUserLimit = snapshot.perUserLimit,
        redemptionTtlSeconds = snapshot.redemptionTtlSeconds,
        policyVersion = snapshot.policyVersion,
        revision = snapshot.revision,
    )

private fun CampaignRecord.auditCampaign(reasonCode: String): AuditRecord =
    AuditRecord(
        id = 0,
        tenantId = tenantId,
        campaignId = campaignId,
        aggregateType = "CAMPAIGN",
        aggregateId = campaignId,
        revision = revision,
        actorType = "OPERATOR",
        reasonCode = reasonCode,
        policyVersion = policyVersion,
        correlationDigest = null,
    )
