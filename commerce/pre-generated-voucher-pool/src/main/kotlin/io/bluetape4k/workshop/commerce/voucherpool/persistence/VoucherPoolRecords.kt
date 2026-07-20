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
)

/** Immutable batch snapshot returned while the batch row lock is held. */
internal data class BatchRecord(
    val tenantId: String,
    val batchId: UUID,
    val campaignId: UUID,
    val state: BatchState,
    val activatesAt: Instant,
    val expiresAt: Instant? = null,
    val importCursor: Long = 0,
    val expectedEntryCount: Long? = null,
    val committedEntryCount: Long = 0,
    val sourceDigest: DigestValue? = null,
    val checkpointDigest: DigestValue? = null,
    val failureCode: String? = null,
    val revision: Long,
)

/** Immutable entry snapshot used by bounded allocation and worker queries. */
internal data class EntryRecord(
    val tenantId: String,
    val entryId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val sourceOrdinal: Long,
    val state: EntryState,
    val reservationId: UUID? = null,
    val allocationId: UUID? = null,
    val userDigest: DigestValue? = null,
    val reservationExpiresAt: Instant? = null,
    val allocationExpiresAt: Instant? = null,
    val codeCiphertext: DigestValue? = null,
    val wrappedDek: DigestValue? = null,
    val codeNonce: DigestValue? = null,
    val wrapNonce: DigestValue? = null,
    val keyVersion: Int = 1,
    val verificationKeyVersion: Int? = null,
    val revealedAt: Instant? = null,
    val quarantinedAt: Instant? = null,
    val revision: Long,
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
)

internal data class LockedWorkerChain(
    val campaign: CampaignRecord,
    val batch: BatchRecord,
    val entry: EntryRecord,
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
