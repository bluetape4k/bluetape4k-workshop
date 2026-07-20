package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import java.time.Instant
import java.util.UUID

/** Immutable campaign snapshot returned while the campaign row lock is held. */
internal data class CampaignRecord(
    val tenantId: String,
    val campaignId: UUID,
    val state: CampaignState,
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
    val revision: Long,
)

/** Immutable user-limit projection locked after campaign and batch guards. */
internal data class UserLimitRecord(
    val tenantId: String,
    val campaignId: UUID,
    val userDigest: ByteArray,
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
)
