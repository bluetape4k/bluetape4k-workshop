@file:Suppress("MagicNumber")

package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

internal object VoucherPoolCampaignTable : Table("voucher_pool_campaigns") {
    val tenantId = varchar("tenant_id", 64)
    val campaignId = javaUUID("campaign_id")
    val state = enumerationByName<CampaignState>("state", 24)
    val startsAt = timestamp("starts_at")
    val endsAt = timestamp("ends_at")
    val perUserLimit = integer("per_user_limit")
    val reservationTtlSeconds = long("reservation_ttl_seconds")
    val allocationTtlSeconds = long("allocation_ttl_seconds")
    val replacementAllowance = integer("replacement_allowance")
    val policyVersion = long("policy_version")
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, campaignId)
}

internal object VoucherPoolBatchTable : Table("voucher_pool_batches") {
    val tenantId = varchar("tenant_id", 64)
    val batchId = javaUUID("batch_id")
    val campaignId = javaUUID("campaign_id")
    val state = enumerationByName<BatchState>("state", 32)
    val activatesAt = timestamp("activates_at")
    val expiresAt = timestamp("expires_at").nullable()
    val importCursor = long("import_cursor")
    val expectedEntryCount = long("expected_entry_count").nullable()
    val committedEntryCount = long("committed_entry_count")
    val sourceDigest = binary("source_digest").nullable()
    val checkpointDigest = binary("checkpoint_digest").nullable()
    val failureCode = varchar("failure_code", 64).nullable()
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, batchId)
}

internal object VoucherPoolEntryTable : Table("voucher_pool_entries") {
    val tenantId = varchar("tenant_id", 64)
    val entryId = javaUUID("entry_id")
    val campaignId = javaUUID("campaign_id")
    val batchId = javaUUID("batch_id")
    val sourceOrdinal = long("source_ordinal")
    val state = enumerationByName<EntryState>("state", 24)
    val reservationId = javaUUID("reservation_id").nullable()
    val allocationId = javaUUID("allocation_id").nullable()
    val userDigest = binary("user_digest").nullable()
    val reservationExpiresAt = timestamp("reservation_expires_at").nullable()
    val allocationExpiresAt = timestamp("allocation_expires_at").nullable()
    val codeCiphertext = binary("code_ciphertext").nullable()
    val wrappedDek = binary("wrapped_dek").nullable()
    val codeNonce = binary("code_nonce")
    val wrapNonce = binary("wrap_nonce")
    val keyVersion = integer("key_version")
    val verificationKeyVersion = integer("verification_key_version").nullable()
    val revealedAt = timestamp("revealed_at").nullable()
    val quarantinedAt = timestamp("quarantined_at").nullable()
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, entryId)
}

internal object VoucherPoolReservationTable : Table("voucher_pool_reservations") {
    val tenantId = varchar("tenant_id", 64)
    val reservationId = javaUUID("reservation_id")
    val entryId = javaUUID("entry_id")
    val campaignId = javaUUID("campaign_id")
    val batchId = javaUUID("batch_id")
    val userDigest = binary("user_digest")
    val idempotencyOwnerDigest = binary("idempotency_owner_digest")
    val state = enumerationByName<ReservationState>("state", 24)
    val expiresAt = timestamp("reservation_expires_at")
    val policyVersion = long("policy_version")
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, reservationId)
}

internal object VoucherPoolAllocationTable : Table("voucher_pool_allocations") {
    val tenantId = varchar("tenant_id", 64)
    val allocationId = javaUUID("allocation_id")
    val reservationId = javaUUID("reservation_id")
    val campaignId = javaUUID("campaign_id")
    val batchId = javaUUID("batch_id")
    val entryId = javaUUID("entry_id")
    val userDigest = binary("user_digest")
    val entitlementRootId = javaUUID("entitlement_root_id")
    val replacementOrdinal = integer("replacement_ordinal")
    val allocationExpiresAt = timestamp("allocation_expires_at")
    val policyVersion = long("policy_version")
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, allocationId)
}

internal object VoucherPoolCodeDedupTable : Table("voucher_pool_code_dedup") {
    val tenantId = varchar("tenant_id", 64)
    val stableDedupDigest = binary("stable_dedup_digest")
    val firstCampaignId = javaUUID("first_campaign_id")
    val firstBatchId = javaUUID("first_batch_id")
    val firstEntryId = javaUUID("first_entry_id")
    val keyVersion = integer("key_version")
    val firstSeenAt = timestamp("first_seen_at")
    override val primaryKey = PrimaryKey(tenantId, stableDedupDigest)
}

internal object VoucherPoolHttpIdempotencyTable : Table("voucher_pool_http_idempotency") {
    val tenantId = varchar("tenant_id", 64)
    val operation = varchar("operation", 64)
    val scopedKeyDigest = binary("scoped_key_digest")
    val fingerprint = binary("fingerprint")
    val status = varchar("status", 24)
    val ownerTokenDigest = binary("owner_token_digest").nullable()
    val leaseUntil = timestamp("lease_until").nullable()
    val commandDeadline = timestamp("command_deadline")
    val descriptor = text("descriptor").nullable()
    val expiresAt = timestamp("expires_at")
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, operation, scopedKeyDigest)
}

internal object VoucherPoolCommandTombstoneTable : Table("voucher_pool_command_tombstones") {
    val tenantId = varchar("tenant_id", 64)
    val operation = varchar("operation", 64)
    val keyVersion = integer("key_version")
    val scopedKeyDigest = binary("scoped_key_digest")
    val fingerprint = binary("fingerprint")
    val effectId = javaUUID("effect_id").nullable()
    val terminalCode = varchar("terminal_code", 64).nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(tenantId, operation, scopedKeyDigest)
}

internal object VoucherPoolAuditTable : Table("voucher_pool_audits") {
    val id = long("id").autoIncrement()
    val tenantId = varchar("tenant_id", 64)
    val campaignId = javaUUID("campaign_id")
    val aggregateType = varchar("aggregate_type", 32)
    val aggregateId = javaUUID("aggregate_id")
    val revision = long("revision")
    val policyVersion = long("policy_version")
    val actorType = varchar("actor_type", 32)
    val reasonCode = varchar("reason_code", 64)
    val correlationDigest = binary("correlation_digest").nullable()
    val requestDigest = binary("request_digest").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object VoucherPoolReconciliationInboxTable : Table("voucher_pool_reconciliation_inbox") {
    val tenantId = varchar("tenant_id", 64)
    val eventId = javaUUID("event_id")
    val payloadDigest = binary("payload_digest")
    val status = varchar("status", 24)
    val attempt = integer("attempt")
    val nextAttemptAt = timestamp("next_attempt_at")
    val claimOwner = varchar("claim_owner", 128).nullable()
    val claimUntil = timestamp("claim_until").nullable()
    val terminalOutcome = varchar("terminal_outcome", 64).nullable()
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, eventId)
}

internal object VoucherPoolQuarantineTable : Table("voucher_pool_quarantines") {
    val tenantId = varchar("tenant_id", 64)
    val entryId = javaUUID("entry_id")
    val sourceState = varchar("source_state", 24)
    val sourceRevision = long("source_revision")
    val reasonCode = varchar("reason_code", 64)
    val detectedAt = timestamp("detected_at")
    val resolvedAt = timestamp("resolved_at").nullable()
    val resolution = varchar("resolution", 64).nullable()
    override val primaryKey = PrimaryKey(tenantId, entryId)
}

internal object VoucherPoolWorkerClaimTable : Table("voucher_pool_worker_claims") {
    val tenantId = varchar("tenant_id", 64)
    val workerType = varchar("worker_type", 32)
    val scopeId = javaUUID("scope_id")
    val ownerId = varchar("owner_id", 128).nullable()
    val claimUntil = timestamp("claim_until").nullable()
    val cursor = long("cursor")
    val attempt = integer("attempt")
    val nextAttemptAt = timestamp("next_attempt_at")
    val checkpoint = long("checkpoint")
    val poisonReason = varchar("poison_reason", 64).nullable()
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, workerType, scopeId)
}

internal object VoucherPoolDepthTable : Table("voucher_pool_pool_depth") {
    val tenantId = varchar("tenant_id", 64)
    val batchId = javaUUID("batch_id")
    val state = enumerationByName<EntryState>("state", 24)
    val entryCount = long("entry_count")
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, batchId, state)
}

internal object VoucherPoolUserLimitTable : Table("voucher_pool_user_limits") {
    val tenantId = varchar("tenant_id", 64)
    val campaignId = javaUUID("campaign_id")
    val userDigest = binary("user_digest", 32)
    val activeReservations = integer("active_reservations")
    val activeAllocations = integer("active_allocations")
    val lifetimeConsumed = integer("lifetime_consumed")
    val revision = long("revision")
    override val primaryKey = PrimaryKey(tenantId, campaignId, userDigest)
}

internal val voucherPoolAuthorityTables = arrayOf(
    VoucherPoolCampaignTable,
    VoucherPoolBatchTable,
    VoucherPoolEntryTable,
    VoucherPoolReservationTable,
    VoucherPoolUserLimitTable,
    VoucherPoolAllocationTable,
    VoucherPoolCodeDedupTable,
    VoucherPoolHttpIdempotencyTable,
    VoucherPoolCommandTombstoneTable,
    VoucherPoolAuditTable,
    VoucherPoolReconciliationInboxTable,
    VoucherPoolQuarantineTable,
    VoucherPoolWorkerClaimTable,
    VoucherPoolDepthTable,
)
