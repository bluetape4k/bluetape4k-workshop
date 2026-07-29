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

internal data class CreateCampaignCommand(
    val tenantId: String,
    val campaignId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
    val perUserLimit: Int,
    val redemptionTtlSeconds: Long,
)

/** claim과 같은 canonical campaign lock을 보유한 상태에서 operator campaign command를 적용합니다. */
@Service
internal class CampaignCommandService(
    private val transactions: VoucherTransactionRunner,
    private val campaigns: CampaignRepository,
    private val audits: AuditRepository,
    private val clock: Clock,
) {
    fun create(command: CreateCampaignCommand): CampaignSnapshot {
        require(command.tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(command.startsAt.isBefore(command.endsAt)) { "startsAt must precede endsAt" }
        require(command.capacity > 0) { "capacity must be positive" }
        require(command.perUserLimit > 0) { "perUserLimit must be positive" }
        require(command.redemptionTtlSeconds > 0) { "redemptionTtlSeconds must be positive" }
        return transactions.foregroundTransaction {
            if (campaigns.findPublic(command.tenantId, command.campaignId) != null) {
                fail(VoucherCommandFailure.CAMPAIGN_ALREADY_EXISTS)
            }
            val created =
                campaigns.create(
                    CampaignRecord(
                        id = 0,
                        tenantId = command.tenantId,
                        campaignId = command.campaignId,
                        state = io.bluetape4k.workshop.commerce.voucher.domain.CampaignState.DRAFT,
                        startsAt = command.startsAt,
                        endsAt = command.endsAt,
                        capacity = command.capacity,
                        allocatedCount = 0,
                        perUserLimit = command.perUserLimit,
                        redemptionTtlSeconds = command.redemptionTtlSeconds,
                        policyVersion = 0,
                        revision = 0,
                    ),
                )
            audits.append(created.auditCampaign("CAMPAIGN_CREATED"))
            log.debug { "voucher_campaign_created campaignId=${created.campaignId}" }
            created.toSnapshot()
        }
    }

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
