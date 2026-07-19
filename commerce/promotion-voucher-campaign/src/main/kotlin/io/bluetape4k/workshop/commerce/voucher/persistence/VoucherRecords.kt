package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.domain.ReviewKind
import java.io.Serializable
import java.time.Instant
import java.util.UUID

internal data class CampaignRecord(
    val id: Long,
    val tenantId: String,
    val campaignId: UUID,
    val state: CampaignState,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
    val allocatedCount: Int,
    val perUserLimit: Int,
    val redemptionTtlSeconds: Long,
    val policyVersion: Long,
    val revision: Long,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable

internal data class ClaimRecord(
    val id: Long,
    val tenantId: String,
    val campaignRowId: Long,
    val campaignId: UUID,
    val claimId: UUID,
    val allocationId: UUID,
    val userDigest: String,
    val state: ClaimState,
    val reviewKind: ReviewKind?,
    val pendingFromState: ClaimState?,
    val capacityReserved: Boolean,
    val allocationPolicyVersion: Long,
    val codeVerifier: ByteArray?,
    val generationKeyVersion: Int?,
    val verificationKeyVersion: Int?,
    val expiresAt: Instant?,
    val redemptionReferenceDigest: String?,
    val revision: Long,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable

internal data class ReviewRecord(
    val id: Long,
    val tenantId: String,
    val campaignId: UUID,
    val claimRowId: Long,
    val claimId: UUID,
    val kind: ReviewKind,
    val status: ReviewStatus,
    val reasonCode: String,
    val signalSummary: String,
    val reviewerActorDigest: String?,
    val expectedClaimRevision: Long,
    val revision: Long,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable

internal data class AuditRecord(
    val id: Long,
    val tenantId: String,
    val campaignId: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val revision: Long,
    val actorType: String,
    val reasonCode: String,
    val policyVersion: Long,
    val correlationDigest: String?,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable

internal data class EventInboxRecord(
    val id: Long,
    val tenantId: String,
    val eventId: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val payloadDigest: String,
    val observedSequence: Long,
    val status: InboxStatus,
    val attempt: Int,
    val nextAttemptAt: Instant,
    val claimOwner: String?,
    val claimUntil: Instant?,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable
