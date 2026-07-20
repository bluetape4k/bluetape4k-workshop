package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import java.time.Instant
import java.util.UUID

/** Immutable binary value with content equality and defensive ingress/egress copies. */
internal class DigestValue private constructor(private val value: ByteArray) {
    fun copyBytes(): ByteArray = value.copyOf()
    override fun equals(other: Any?): Boolean = other is DigestValue && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "DigestValue([REDACTED])"

    companion object {
        fun of(value: ByteArray): DigestValue = DigestValue(value.copyOf())
    }
}

/** Immutable campaign snapshot returned while the campaign row lock is held. */
internal data class CampaignRecord(
    val tenantId: String,
    val campaignId: UUID,
    val state: CampaignState,
    val startsAt: Instant = Instant.EPOCH,
    val endsAt: Instant = Instant.MAX,
    val perUserLimit: Int = 1,
    val reservationTtlSeconds: Long = 1,
    val allocationTtlSeconds: Long = 1,
    val replacementAllowance: Int = 0,
    val policyVersion: Long,
    val revision: Long,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)

/** Immutable batch snapshot returned while the batch row lock is held. */
internal data class BatchRecord(
    val tenantId: String,
    val batchId: UUID,
    val campaignId: UUID,
    val state: BatchState,
    val sourceKind: String,
    val provenanceDigest: DigestValue,
    val requestFingerprint: DigestValue,
    val policyVersion: Long,
    val activatesAt: Instant,
    val expiresAt: Instant? = null,
    val nextSourceOrdinal: Long = 0,
    val expectedCount: Long = 0,
    val acceptedCount: Long = 0,
    val rejectedCount: Long = 0,
    val checkpointDigest: DigestValue? = null,
    val lastFailureCode: String? = null,
    val revision: Long,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)

/** Immutable entry snapshot used by bounded allocation and worker queries. */
internal data class EntryRecord(
    val tenantId: String,
    val entryId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val sourceOrdinal: Long,
    val state: EntryState,
    val stableDedupDigest: DigestValue,
    /** Null while staged; Task 7 sets the allocation-scoped digest and version together. */
    val verificationDigest: DigestValue?,
    val verificationKeyVersion: Int?,
    val codeCiphertext: DigestValue? = null,
    val codeNonce: DigestValue? = null,
    val wrappedDek: DigestValue? = null,
    val wrapNonce: DigestValue? = null,
    val kekVersion: String? = null,
    val reservationId: UUID? = null,
    val allocationId: UUID? = null,
    val userDigest: DigestValue? = null,
    val reservedAt: Instant? = null,
    val reservationExpiresAt: Instant? = null,
    val allocatedAt: Instant? = null,
    val allocationExpiresAt: Instant? = null,
    val revealedAt: Instant? = null,
    val redeemedAt: Instant? = null,
    val allocationPolicyVersion: Long? = null,
    val terminalReason: String? = null,
    val entitlementRootId: UUID? = null,
    val replacementCount: Int = 0,
    val quarantinedAt: Instant? = null,
    val revision: Long,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)

/** Fully validated and encrypted entry buffer ready for one bounded chunk transaction. */
internal data class PreparedVoucherEntryRecord(
    val tenantId: String,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val sourceOrdinal: Long,
    val stableDedupDigest: DigestValue,
    val stableDedupKeyVersion: Int,
    val codeCiphertext: DigestValue,
    val codeNonce: DigestValue,
    val wrappedDek: DigestValue,
    val wrapNonce: DigestValue,
    val kekVersion: String,
)

/** Redacted validation evidence retained without source material. */
internal data class RejectedVoucherEntryRecord(
    val sourceOrdinal: Long,
    val reasonCode: String,
    val payloadDigest: DigestValue,
)

/** Stable digest authority for an already committed source ordinal. */
internal data class CommittedOrdinalDigest(
    val sourceOrdinal: Long,
    val stableDedupDigest: DigestValue,
)

/** Physical ordinal coverage used by the activation gate. */
internal data class BatchOrdinalCoverage(
    val entryCount: Long,
    val minimumOrdinal: Long?,
    val maximumOrdinal: Long?,
)

/** Crypto material returned while the allocated entry row lock is held. */
internal data class LockedVoucherCryptoRecord(
    val state: EntryState,
    val revision: Long,
    val stableDedupDigest: DigestValue?,
    val stableDedupKeyVersion: Int,
    val codeCiphertext: DigestValue?,
    val codeNonce: DigestValue?,
    val wrappedDek: DigestValue?,
    val wrapNonce: DigestValue?,
    val kekVersion: String?,
)

/** Immutable user-limit projection locked after campaign and batch guards. */
internal data class UserLimitRecord(
    val tenantId: String,
    val campaignId: UUID,
    val userDigest: DigestValue,
    val activeReservations: Int,
    val activeAllocations: Int,
    val lifetimeConsumed: Int,
    val revision: Long,
)

internal data class WorkerCandidate(
    val tenantId: String,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val expectedCampaignRevision: Long,
    val expectedBatchRevision: Long,
    val expectedEntryRevision: Long,
    val userLimits: List<ExpectedUserLimit> = emptyList(),
    val reservations: List<ExpectedReservation> = emptyList(),
)

internal data class ExpectedUserLimit(
    val userDigest: DigestValue,
    val expectedRevision: Long,
)

internal data class ExpectedReservation(
    val reservationId: UUID,
    val expectedRevision: Long,
)

internal data class LockedWorkerChain(
    val campaign: CampaignRecord,
    val batch: BatchRecord,
    val entry: EntryRecord,
    val userLimits: List<UserLimitRecord> = emptyList(),
    val reservations: List<ReservationRecord> = emptyList(),
)

internal data class VoucherPoolAuditRecord(
    val tenantId: String,
    val campaignId: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val revision: Long,
    val policyVersion: Long,
    val actorType: String,
    val reasonCode: String,
    val correlationDigest: DigestValue? = null,
    val requestDigest: DigestValue? = null,
)

internal data class ReservationRecord(
    val tenantId: String,
    val reservationId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val userDigest: DigestValue,
    val idempotencyOwnerDigest: DigestValue,
    val state: String,
    val expiresAt: Instant,
    val policyVersion: Long,
    val revision: Long,
)

internal data class AllocationRecord(
    val tenantId: String,
    val allocationId: UUID,
    val reservationId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val userDigest: DigestValue,
    val entitlementRootId: UUID,
    val replacementOrdinal: Int,
    val expiresAt: Instant,
    val policyVersion: Long,
    val revision: Long,
)

internal data class CodeDedupRecord(
    val tenantId: String,
    val stableDigest: DigestValue,
    val firstCampaignId: UUID,
    val firstBatchId: UUID,
    val firstEntryId: UUID,
    val keyVersion: Int,
    val firstSeenAt: Instant,
)

internal data class HttpIdempotencyRecord(
    val tenantId: String,
    val operation: String,
    val scopedKeyDigest: DigestValue,
    val fingerprint: DigestValue,
    val status: String,
    val ownerTokenDigest: DigestValue?,
    val leaseUntil: Instant?,
    val commandDeadline: Instant,
    val descriptor: String?,
    val expiresAt: Instant,
    val revision: Long,
)

internal data class CommandTombstoneRecord(
    val tenantId: String,
    val operation: String,
    val keyVersion: Int,
    val scopedKeyDigest: DigestValue,
    val fingerprint: DigestValue,
    val effectId: UUID?,
    val terminalCode: String?,
    val createdAt: Instant,
)

internal data class ReconciliationInboxRecord(
    val tenantId: String,
    val eventId: UUID,
    val payloadDigest: DigestValue,
    val status: String,
    val attempt: Int,
    val nextAttemptAt: Instant,
    val claimOwner: String?,
    val claimUntil: Instant?,
    val terminalOutcome: String?,
    val revision: Long,
)

internal data class QuarantineRecord(
    val tenantId: String,
    val entryId: UUID,
    val sourceState: EntryState,
    val sourceRevision: Long,
    val reasonCode: String,
    val detectedAt: Instant,
    val resolvedAt: Instant?,
    val resolution: String?,
)

internal data class WorkerClaimRecord(
    val tenantId: String,
    val workerType: String,
    val scopeId: UUID,
    val ownerId: String?,
    val claimUntil: Instant?,
    val cursor: Long,
    val attempt: Int,
    val nextAttemptAt: Instant,
    val checkpoint: Long,
    val poisonReason: String?,
    val revision: Long,
)

internal data class PoolDepthRecord(
    val tenantId: String,
    val batchId: UUID,
    val state: EntryState,
    val entryCount: Long,
    val revision: Long,
)
