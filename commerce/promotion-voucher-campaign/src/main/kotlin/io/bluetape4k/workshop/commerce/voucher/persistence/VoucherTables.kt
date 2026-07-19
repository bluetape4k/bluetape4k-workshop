package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.domain.ReviewKind
import io.bluetape4k.workshop.commerce.voucher.idempotency.HttpIdempotencyTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.javatime.timestamp

internal enum class ReviewStatus {
    OPEN,
    APPROVED,
    REJECTED,
}

internal enum class InboxStatus {
    PENDING,
    CLAIMED,
    APPLIED,
    IGNORED,
    CONFLICT,
    FAILED,
}

/** Tenant-scoped campaign capacity authority with an in-row capacity invariant. */
internal object CampaignTable : AuditableLongIdTable("voucher_campaigns") {
    val tenantId = varchar("tenant_id", 64)
    val campaignId = javaUUID("campaign_id")
    val state = enumerationByName<CampaignState>("state", 24)
    val startsAt = timestamp("starts_at")
    val endsAt = timestamp("ends_at")
    val capacity = integer("capacity")
    val allocatedCount = integer("allocated_count").default(0)
    val perUserLimit = integer("per_user_limit")
    val redemptionTtlSeconds = long("redemption_ttl_seconds")
    val policyVersion = long("policy_version")
    val revision = long("revision").default(0)

    init {
        uniqueIndex(tenantId, campaignId)
        index(false, tenantId, state, endsAt)
        check("voucher_campaign_capacity") {
            (allocatedCount greaterEq 0) and (allocatedCount lessEq capacity)
        }
    }
}

/** Stores only opaque-code verifiers and version references; plaintext code has no column. */
internal object ClaimTable : AuditableLongIdTable("voucher_claims") {
    val tenantId = varchar("tenant_id", 64)
    val campaignRowId = reference("campaign_row_id", CampaignTable, onDelete = ReferenceOption.RESTRICT)
    val campaignId = javaUUID("campaign_id")
    val claimId = javaUUID("claim_id")
    val allocationId = javaUUID("allocation_id")
    val userDigest = char("user_digest", 64)
    val state = enumerationByName<ClaimState>("state", 32)
    val reviewKind = enumerationByName<ReviewKind>("review_kind", 24).nullable()
    val pendingFromState = enumerationByName<ClaimState>("pending_from_state", 32).nullable()
    val capacityReserved = bool("capacity_reserved")
    val allocationPolicyVersion = long("allocation_policy_version")
    val codeVerifier = binary("code_verifier", 32).nullable()
    val generationKeyVersion = integer("generation_key_version").nullable()
    val verificationKeyVersion = integer("verification_key_version").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val redemptionReferenceDigest = char("redemption_reference_digest", 64).nullable()
    val revision = long("revision").default(0)

    init {
        uniqueIndex(tenantId, claimId)
        uniqueIndex(tenantId, allocationId)
        uniqueIndex(tenantId, codeVerifier)
        uniqueIndex(tenantId, redemptionReferenceDigest)
        index(false, tenantId, campaignId, userDigest, state)
        index(false, state, expiresAt, id)
    }
}

internal object ReviewTable : AuditableLongIdTable("voucher_reviews") {
    val tenantId = varchar("tenant_id", 64)
    val campaignId = javaUUID("campaign_id")
    val claimRowId = reference("claim_row_id", ClaimTable, onDelete = ReferenceOption.RESTRICT)
    val claimId = javaUUID("claim_id")
    val kind = enumerationByName<ReviewKind>("kind", 24)
    val status = enumerationByName<ReviewStatus>("status", 24).default(ReviewStatus.OPEN)
    val reasonCode = varchar("reason_code", 64)
    val signalSummary = varchar("signal_summary", 256)
    val reviewerActorDigest = char("reviewer_actor_digest", 64).nullable()
    val expectedClaimRevision = long("expected_claim_revision")
    val revision = long("revision").default(0)

    init {
        index(false, tenantId, status, createdAt, id)
        uniqueIndex(tenantId, claimId, kind, revision)
    }
}

internal object AuditTable : AuditableLongIdTable("voucher_audits") {
    val tenantId = varchar("tenant_id", 64)
    val campaignId = javaUUID("campaign_id")
    val aggregateType = varchar("aggregate_type", 32)
    val aggregateId = javaUUID("aggregate_id")
    val revision = long("revision")
    val actorType = varchar("actor_type", 32)
    val reasonCode = varchar("reason_code", 64)
    val policyVersion = long("policy_version")
    val correlationDigest = char("correlation_digest", 64).nullable()

    init {
        uniqueIndex(tenantId, aggregateType, aggregateId, revision)
        index(false, tenantId, campaignId, revision, id)
    }
}

internal object EventInboxTable : AuditableLongIdTable("campaign_event_inbox") {
    val tenantId = varchar("tenant_id", 64)
    val eventId = javaUUID("event_id")
    val aggregateType = varchar("aggregate_type", 32)
    val aggregateId = javaUUID("aggregate_id")
    val payloadDigest = char("payload_digest", 64)
    val observedSequence = long("observed_sequence")
    val status = enumerationByName<InboxStatus>("status", 24).default(InboxStatus.PENDING)
    val attempt = integer("attempt").default(0)
    val nextAttemptAt = timestamp("next_attempt_at")
    val claimOwner = varchar("claim_owner", 128).nullable()
    val claimUntil = timestamp("claim_until").nullable()

    init {
        uniqueIndex(tenantId, eventId)
        index(false, status, nextAttemptAt, id)
    }
}

internal object VoucherSchemaHistoryTable : Table("voucher_schema_history") {
    val version = varchar("version", 64)
    val checksum = varchar("checksum", 64)
    val appliedAt = timestamp("applied_at")
    override val primaryKey = PrimaryKey(version)
}

internal val voucherTables =
    arrayOf(
        CampaignTable,
        ClaimTable,
        ReviewTable,
        AuditTable,
        EventInboxTable,
        HttpIdempotencyTable,
    )
