@file:Suppress("LongParameterList", "MagicNumber", "TooManyFunctions", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.query

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAllocationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBatchTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCampaignTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolEntryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolReservationTable
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.io.Serializable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.util.*

internal data class ReservationReadModel(
    val reservationId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val state: ReservationState,
    val expiresAt: Instant,
    val entitlementRootId: UUID?,
    val replacementOrdinal: Int,
    val policyVersion: Long,
    val revision: Long,
    val observedAt: Instant,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class AllocationReadModel(
    val allocationId: UUID,
    val reservationId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val state: EntryState,
    val expiresAt: Instant,
    val entitlementRootId: UUID,
    val replacementOrdinal: Int,
    val policyVersion: Long,
    val revision: Long,
    val observedAt: Instant,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class BatchReadModel(
    val batchId: UUID,
    val campaignId: UUID,
    val state: BatchState,
    val sourceKind: String,
    val activatesAt: Instant,
    val expiresAt: Instant?,
    val nextSourceOrdinal: Long,
    val expectedCount: Long,
    val acceptedCount: Long,
    val rejectedCount: Long,
    val lastFailureCode: String?,
    val revision: Long,
    val nextAction: String,
    val observedAt: Instant,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class CampaignReadModel(
    val campaignId: UUID,
    val state: CampaignState,
    val startsAt: Instant,
    val endsAt: Instant,
    val perUserLimit: Int,
    val reservationTtlSeconds: Long,
    val allocationTtlSeconds: Long,
    val replacementAllowance: Int,
    val policyVersion: Long,
    val revision: Long,
    val observedAt: Instant,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class PoolDepthReadModel(
    val campaignId: UUID?,
    val batchId: UUID?,
    val counts: Map<EntryState, Long>,
    val eligibleAvailable: Long,
    val expiredButNotTerminalized: Long,
    val observedAt: Instant,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class StuckReservationCursor(
    val expiresAt: Instant,
    val reservationId: UUID,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class StuckReservationReadModel(
    val reservationId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val state: ReservationState,
    val expiresAt: Instant,
    val revision: Long,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class StuckReservationPage(
    val items: List<StuckReservationReadModel>,
    val nextCursor: StuckReservationCursor?,
    val observedAt: Instant,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal interface VoucherPoolQueryService {
    fun reservation(tenantId: String, principalId: String, reservationId: UUID): ReservationReadModel?
    fun allocation(tenantId: String, principalId: String, allocationId: UUID): AllocationReadModel?
    fun campaign(tenantId: String, campaignId: UUID): CampaignReadModel?
    fun batch(tenantId: String, batchId: UUID): BatchReadModel?
    fun poolDepth(tenantId: String, campaignId: UUID?, batchId: UUID?): PoolDepthReadModel?
    fun stuckReservations(
        tenantId: String,
        campaignId: UUID?,
        cursor: StuckReservationCursor?,
        limit: Int,
    ): StuckReservationPage?
}

internal class JdbcVoucherPoolQueryService(
    private val executor: VoucherPoolJdbcExecutor,
    private val store: VoucherPoolQueryStore,
    private val digests: VoucherDigestService,
): VoucherPoolQueryService {

    private companion object: KLogging() {
        const val MAX_PAGE_SIZE = 100
    }

    override fun reservation(tenantId: String, principalId: String, reservationId: UUID): ReservationReadModel? =
        executor.foregroundTransaction {
            val campaignId = store.resolveReservationCampaign(tenantId, reservationId)
                ?: return@foregroundTransaction null

            withUserDigest(tenantId, campaignId, principalId) { ownerDigest ->
                store.findOwnedReservation(tenantId, reservationId, ownerDigest)
            }
        }

    override fun allocation(tenantId: String, principalId: String, allocationId: UUID): AllocationReadModel? =
        executor.foregroundTransaction {
            val campaignId = store.resolveAllocationCampaign(tenantId, allocationId)
                ?: return@foregroundTransaction null

            withUserDigest(tenantId, campaignId, principalId) { ownerDigest ->
                store.findOwnedAllocation(tenantId, allocationId, ownerDigest)
            }
        }

    override fun batch(tenantId: String, batchId: UUID): BatchReadModel? =
        executor.operatorTransaction { store.findBatch(tenantId, batchId) }

    override fun campaign(tenantId: String, campaignId: UUID): CampaignReadModel? =
        executor.operatorTransaction { store.findCampaign(tenantId, campaignId) }

    override fun poolDepth(tenantId: String, campaignId: UUID?, batchId: UUID?): PoolDepthReadModel? =
        executor.operatorTransaction {
            if (!store.scopeExists(tenantId, campaignId, batchId)) return@operatorTransaction null
            store.readPoolDepth(tenantId, campaignId, batchId)
        }

    override fun stuckReservations(
        tenantId: String,
        campaignId: UUID?,
        cursor: StuckReservationCursor?,
        limit: Int,
    ): StuckReservationPage? {
        limit.requireInRange(1, MAX_PAGE_SIZE, "limit")

        return executor.operatorTransaction {
            if (!store.scopeExists(tenantId, campaignId, null)) return@operatorTransaction null
            store.findStuckReservations(tenantId, campaignId, cursor, limit)
        }
    }

    private inline fun <T> withUserDigest(
        tenantId: String,
        campaignId: UUID,
        principalId: String,
        block: (ByteArray) -> T,
    ): T {
        val keyVersion = checkNotNull(store.findUserIdentityKeyVersion(tenantId, campaignId))
        val digest = digests.userIdentity(tenantId, campaignId, principalId, keyVersion).copyBytes()

        return try {
            block(digest)
        } finally {
            digest.fill(0)
        }
    }
}

internal interface VoucherPoolQueryStore {
    fun findUserIdentityKeyVersion(tenantId: String, campaignId: UUID): Int?
    fun scopeExists(tenantId: String, campaignId: UUID?, batchId: UUID?): Boolean
    fun resolveReservationCampaign(tenantId: String, reservationId: UUID): UUID?
    fun findOwnedReservation(tenantId: String, reservationId: UUID, ownerDigest: ByteArray): ReservationReadModel?
    fun resolveAllocationCampaign(tenantId: String, allocationId: UUID): UUID?
    fun findOwnedAllocation(tenantId: String, allocationId: UUID, ownerDigest: ByteArray): AllocationReadModel?
    fun findCampaign(tenantId: String, campaignId: UUID): CampaignReadModel?
    fun findBatch(tenantId: String, batchId: UUID): BatchReadModel?
    fun readPoolDepth(tenantId: String, campaignId: UUID?, batchId: UUID?): PoolDepthReadModel
    fun findStuckReservations(
        tenantId: String,
        campaignId: UUID?,
        cursor: StuckReservationCursor?,
        limit: Int,
    ): StuckReservationPage
}

internal class JdbcVoucherPoolQueryStore: VoucherPoolQueryStore {
    private companion object: KLogging()

    override fun findUserIdentityKeyVersion(tenantId: String, campaignId: UUID): Int? =
        VoucherPoolCampaignTable
            .select(VoucherPoolCampaignTable.userIdentityKeyVersion)
            .where {
                (VoucherPoolCampaignTable.tenantId eq tenantId) and
                        (VoucherPoolCampaignTable.campaignId eq campaignId)
            }
            .singleOrNull()
            ?.get(VoucherPoolCampaignTable.userIdentityKeyVersion)

    override fun scopeExists(tenantId: String, campaignId: UUID?, batchId: UUID?): Boolean {
        if (campaignId == null && batchId == null) return true
        return when {
            campaignId != null && batchId != null ->
                VoucherPoolBatchTable
                    .select(VoucherPoolBatchTable.batchId)
                    .where {
                        (VoucherPoolBatchTable.tenantId eq tenantId) and
                                (VoucherPoolBatchTable.campaignId eq campaignId) and
                                (VoucherPoolBatchTable.batchId eq batchId)
                    }
                    .limit(1)
                    .singleOrNull() != null
            campaignId != null ->
                VoucherPoolCampaignTable
                    .select(VoucherPoolCampaignTable.campaignId)
                    .where {
                        (VoucherPoolCampaignTable.tenantId eq tenantId) and
                                (VoucherPoolCampaignTable.campaignId eq campaignId)
                    }
                    .limit(1)
                    .singleOrNull() != null
            else ->
                VoucherPoolBatchTable
                    .select(VoucherPoolBatchTable.batchId)
                    .where {
                        (VoucherPoolBatchTable.tenantId eq tenantId) and
                                (VoucherPoolBatchTable.batchId eq checkNotNull(batchId))
                    }
                    .limit(1)
                    .singleOrNull() != null
        }
    }

    override fun resolveReservationCampaign(tenantId: String, reservationId: UUID): UUID? =
        VoucherPoolReservationTable
            .select(VoucherPoolReservationTable.campaignId)
            .where {
                (VoucherPoolReservationTable.tenantId eq tenantId) and
                        (VoucherPoolReservationTable.reservationId eq reservationId)
            }
            .singleOrNull()
            ?.get(VoucherPoolReservationTable.campaignId)

    override fun findOwnedReservation(
        tenantId: String,
        reservationId: UUID,
        ownerDigest: ByteArray,
    ): ReservationReadModel? {
        val observedAt = currentConnection().observedAt()
        return VoucherPoolReservationTable
            .select(
                VoucherPoolReservationTable.reservationId,
                VoucherPoolReservationTable.campaignId,
                VoucherPoolReservationTable.batchId,
                VoucherPoolReservationTable.entryId,
                VoucherPoolReservationTable.state,
                VoucherPoolReservationTable.expiresAt,
                VoucherPoolReservationTable.entitlementRootId,
                VoucherPoolReservationTable.replacementOrdinal,
                VoucherPoolReservationTable.policyVersion,
                VoucherPoolReservationTable.revision,
            )
            .where {
                (VoucherPoolReservationTable.tenantId eq tenantId) and
                        (VoucherPoolReservationTable.reservationId eq reservationId) and
                        (VoucherPoolReservationTable.userDigest eq ownerDigest)
            }
            .singleOrNull()
            ?.let { row ->
                ReservationReadModel(
                    reservationId = row[VoucherPoolReservationTable.reservationId],
                    campaignId = row[VoucherPoolReservationTable.campaignId],
                    batchId = row[VoucherPoolReservationTable.batchId],
                    entryId = row[VoucherPoolReservationTable.entryId],
                    state = row[VoucherPoolReservationTable.state],
                    expiresAt = row[VoucherPoolReservationTable.expiresAt],
                    entitlementRootId = row[VoucherPoolReservationTable.entitlementRootId],
                    replacementOrdinal = row[VoucherPoolReservationTable.replacementOrdinal],
                    policyVersion = row[VoucherPoolReservationTable.policyVersion],
                    revision = row[VoucherPoolReservationTable.revision],
                    observedAt = observedAt,
                )
            }
    }

    override fun resolveAllocationCampaign(tenantId: String, allocationId: UUID): UUID? =
        VoucherPoolAllocationTable
            .select(VoucherPoolAllocationTable.campaignId)
            .where {
                (VoucherPoolAllocationTable.tenantId eq tenantId) and
                        (VoucherPoolAllocationTable.allocationId eq allocationId)
            }
            .singleOrNull()
            ?.get(VoucherPoolAllocationTable.campaignId)

    override fun findOwnedAllocation(
        tenantId: String,
        allocationId: UUID,
        ownerDigest: ByteArray,
    ): AllocationReadModel? {
        val observedAt = currentConnection().observedAt()
        val joined = VoucherPoolAllocationTable.innerJoin(
            VoucherPoolEntryTable,
            { VoucherPoolAllocationTable.tenantId },
            { VoucherPoolEntryTable.tenantId },
            additionalConstraint = { VoucherPoolAllocationTable.entryId eq VoucherPoolEntryTable.entryId },
        )
        return joined
            .select(
                VoucherPoolAllocationTable.allocationId,
                VoucherPoolAllocationTable.reservationId,
                VoucherPoolAllocationTable.campaignId,
                VoucherPoolAllocationTable.batchId,
                VoucherPoolAllocationTable.entryId,
                VoucherPoolEntryTable.state,
                VoucherPoolAllocationTable.allocationExpiresAt,
                VoucherPoolAllocationTable.entitlementRootId,
                VoucherPoolAllocationTable.replacementOrdinal,
                VoucherPoolAllocationTable.policyVersion,
                VoucherPoolAllocationTable.revision,
            )
            .where {
                (VoucherPoolAllocationTable.tenantId eq tenantId) and
                        (VoucherPoolAllocationTable.allocationId eq allocationId) and
                        (VoucherPoolAllocationTable.userDigest eq ownerDigest)
            }
            .singleOrNull()
            ?.let { row ->
                AllocationReadModel(
                    allocationId = row[VoucherPoolAllocationTable.allocationId],
                    reservationId = row[VoucherPoolAllocationTable.reservationId],
                    campaignId = row[VoucherPoolAllocationTable.campaignId],
                    batchId = row[VoucherPoolAllocationTable.batchId],
                    entryId = row[VoucherPoolAllocationTable.entryId],
                    state = row[VoucherPoolEntryTable.state],
                    expiresAt = row[VoucherPoolAllocationTable.allocationExpiresAt],
                    entitlementRootId = row[VoucherPoolAllocationTable.entitlementRootId],
                    replacementOrdinal = row[VoucherPoolAllocationTable.replacementOrdinal],
                    policyVersion = row[VoucherPoolAllocationTable.policyVersion],
                    revision = row[VoucherPoolAllocationTable.revision],
                    observedAt = observedAt,
                )
            }
    }

    override fun findBatch(tenantId: String, batchId: UUID): BatchReadModel? {
        val observedAt = currentConnection().observedAt()
        return VoucherPoolBatchTable
            .select(
                VoucherPoolBatchTable.batchId,
                VoucherPoolBatchTable.campaignId,
                VoucherPoolBatchTable.state,
                VoucherPoolBatchTable.sourceKind,
                VoucherPoolBatchTable.activatesAt,
                VoucherPoolBatchTable.expiresAt,
                VoucherPoolBatchTable.nextSourceOrdinal,
                VoucherPoolBatchTable.expectedCount,
                VoucherPoolBatchTable.acceptedCount,
                VoucherPoolBatchTable.rejectedCount,
                VoucherPoolBatchTable.lastFailureCode,
                VoucherPoolBatchTable.revision,
            )
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.batchId eq batchId)
            }
            .singleOrNull()
            ?.let { row ->
                val state = row[VoucherPoolBatchTable.state]
                BatchReadModel(
                    batchId = row[VoucherPoolBatchTable.batchId],
                    campaignId = row[VoucherPoolBatchTable.campaignId],
                    state = state,
                    sourceKind = row[VoucherPoolBatchTable.sourceKind],
                    activatesAt = row[VoucherPoolBatchTable.activatesAt],
                    expiresAt = row[VoucherPoolBatchTable.expiresAt],
                    nextSourceOrdinal = row[VoucherPoolBatchTable.nextSourceOrdinal],
                    expectedCount = row[VoucherPoolBatchTable.expectedCount],
                    acceptedCount = row[VoucherPoolBatchTable.acceptedCount],
                    rejectedCount = row[VoucherPoolBatchTable.rejectedCount],
                    lastFailureCode = row[VoucherPoolBatchTable.lastFailureCode],
                    revision = row[VoucherPoolBatchTable.revision],
                    nextAction = state.nextAction(),
                    observedAt = observedAt,
                )
            }
    }

    override fun findCampaign(tenantId: String, campaignId: UUID): CampaignReadModel? {
        val observedAt = currentConnection().observedAt()
        return VoucherPoolCampaignTable
            .select(
                VoucherPoolCampaignTable.campaignId,
                VoucherPoolCampaignTable.state,
                VoucherPoolCampaignTable.startsAt,
                VoucherPoolCampaignTable.endsAt,
                VoucherPoolCampaignTable.perUserLimit,
                VoucherPoolCampaignTable.reservationTtlSeconds,
                VoucherPoolCampaignTable.allocationTtlSeconds,
                VoucherPoolCampaignTable.replacementAllowance,
                VoucherPoolCampaignTable.policyVersion,
                VoucherPoolCampaignTable.revision,
            )
            .where {
                (VoucherPoolCampaignTable.tenantId eq tenantId) and
                        (VoucherPoolCampaignTable.campaignId eq campaignId)
            }
            .singleOrNull()
            ?.let { row ->
                CampaignReadModel(
                    campaignId = row[VoucherPoolCampaignTable.campaignId],
                    state = row[VoucherPoolCampaignTable.state],
                    startsAt = row[VoucherPoolCampaignTable.startsAt],
                    endsAt = row[VoucherPoolCampaignTable.endsAt],
                    perUserLimit = row[VoucherPoolCampaignTable.perUserLimit],
                    reservationTtlSeconds = row[VoucherPoolCampaignTable.reservationTtlSeconds],
                    allocationTtlSeconds = row[VoucherPoolCampaignTable.allocationTtlSeconds],
                    replacementAllowance = row[VoucherPoolCampaignTable.replacementAllowance],
                    policyVersion = row[VoucherPoolCampaignTable.policyVersion],
                    revision = row[VoucherPoolCampaignTable.revision],
                    observedAt = observedAt,
                )
            }
    }

    /**
     * pool-depth는 scoped CTE, FILTER aggregate, expiry derivation, transaction snapshot을 한 statement로 묶습니다.
     * 이 PostgreSQL 전용 read-model 계산은 Exposed DSL로 분해하면 snapshot 경계가 달라지므로 raw로 보존합니다.
     */
    @Suppress("LongMethod", "NestedBlockDepth") // Keep the one-statement MVCC snapshot and its parser adjacent.
    override fun readPoolDepth(tenantId: String, campaignId: UUID?, batchId: UUID?): PoolDepthReadModel {
        val connection = currentConnection()
        val clauses = mutableListOf("b.tenant_id=?")
        if (campaignId != null) clauses += "b.campaign_id=?"
        if (batchId != null) clauses += "b.batch_id=?"

        val sql = """WITH scoped_batches AS (
                    SELECT b.tenant_id,b.campaign_id,b.batch_id,b.state,b.activates_at,b.expires_at
                    FROM voucher_pool_batches b
                    WHERE ${clauses.joinToString(" AND ")}
                ), raw_depth AS (
                    SELECT d.state,SUM(d.entry_count) AS entry_count
                    FROM voucher_pool_pool_depth d
                    JOIN scoped_batches b ON b.tenant_id=d.tenant_id AND b.batch_id=d.batch_id
                    GROUP BY d.state
                ), derived_depth AS (
                    SELECT
                        COUNT(e.entry_id) FILTER (WHERE e.state='AVAILABLE' AND e.quarantined_at IS NULL
                            AND c.state='ACTIVE' AND c.starts_at<=transaction_timestamp()
                            AND c.ends_at>transaction_timestamp() AND b.state='ACTIVE'
                            AND b.activates_at<=transaction_timestamp()
                            AND (b.expires_at IS NULL OR b.expires_at>transaction_timestamp())) AS eligible_available,
                        COUNT(e.entry_id) FILTER (WHERE e.state IN ('AVAILABLE','RESERVED','ALLOCATED') AND
                            ((e.state='RESERVED' AND e.reservation_expires_at<=transaction_timestamp())
                             OR (e.state='ALLOCATED' AND e.allocation_expires_at<=transaction_timestamp())
                             OR (b.expires_at IS NOT NULL AND b.expires_at<=transaction_timestamp())))
                            AS expired_not_terminalized
                    FROM scoped_batches b
                    JOIN voucher_pool_campaigns c
                      ON c.tenant_id=b.tenant_id AND c.campaign_id=b.campaign_id
                    LEFT JOIN voucher_pool_entries e
                      ON e.tenant_id=b.tenant_id AND e.batch_id=b.batch_id
                )
                SELECT r.state,r.entry_count,d.eligible_available,d.expired_not_terminalized,
                       transaction_timestamp()
                FROM derived_depth d
                LEFT JOIN raw_depth r ON TRUE
                ORDER BY r.state""".trimIndent()

        return connection.prepareStatement(sql).use { statement ->
            statement.bindScope(tenantId, campaignId, batchId)
            statement.executeQuery().use { result ->
                val counts = mutableMapOf<EntryState, Long>()
                var eligibleAvailable = 0L
                var expiredButNotTerminalized = 0L
                var observedAt: Instant? = null

                while (result.next()) {
                    result.getString(1)
                        ?.let { state ->
                            counts[EntryState.valueOf(state)] = result.getLong(2)
                        }
                    eligibleAvailable = result.getLong(3)
                    expiredButNotTerminalized = result.getLong(4)
                    observedAt = result.getTimestamp(5).toInstant()
                }
                PoolDepthReadModel(
                    campaignId = campaignId,
                    batchId = batchId,
                    counts = counts,
                    eligibleAvailable = eligibleAvailable,
                    expiredButNotTerminalized = expiredButNotTerminalized,
                    observedAt = checkNotNull(observedAt),
                )
            }
        }
    }

    override fun findStuckReservations(
        tenantId: String,
        campaignId: UUID?,
        cursor: StuckReservationCursor?,
        limit: Int,
    ): StuckReservationPage {
        val observedAt = currentConnection().observedAt()
        val rows = VoucherPoolReservationTable
            .select(
                VoucherPoolReservationTable.reservationId,
                VoucherPoolReservationTable.campaignId,
                VoucherPoolReservationTable.batchId,
                VoucherPoolReservationTable.entryId,
                VoucherPoolReservationTable.state,
                VoucherPoolReservationTable.expiresAt,
                VoucherPoolReservationTable.revision,
            )
            .where {
                var predicate =
                    (VoucherPoolReservationTable.tenantId eq tenantId) and
                            (VoucherPoolReservationTable.state eq ReservationState.ACTIVE) and
                            (VoucherPoolReservationTable.expiresAt lessEq observedAt)
                campaignId?.let {
                    predicate = predicate and (VoucherPoolReservationTable.campaignId eq it)
                }
                cursor?.let {
                    predicate = predicate and
                            ((VoucherPoolReservationTable.expiresAt greater it.expiresAt) or
                                    ((VoucherPoolReservationTable.expiresAt eq it.expiresAt) and
                                            (VoucherPoolReservationTable.reservationId greater it.reservationId)))
                }
                predicate
            }
            .orderBy(
                VoucherPoolReservationTable.expiresAt to SortOrder.ASC,
                VoucherPoolReservationTable.reservationId to SortOrder.ASC,
            )
            .limit(limit + 1)
            .map { row ->
                StuckReservationReadModel(
                    reservationId = row[VoucherPoolReservationTable.reservationId],
                    campaignId = row[VoucherPoolReservationTable.campaignId],
                    batchId = row[VoucherPoolReservationTable.batchId],
                    entryId = row[VoucherPoolReservationTable.entryId],
                    state = row[VoucherPoolReservationTable.state],
                    expiresAt = row[VoucherPoolReservationTable.expiresAt],
                    revision = row[VoucherPoolReservationTable.revision],
                )
            }
        val items = rows.take(limit)
        val nextCursor = if (rows.size > limit) {
            items.last().let { StuckReservationCursor(it.expiresAt, it.reservationId) }
        } else {
            null
        }
        return StuckReservationPage(items, nextCursor, observedAt)
    }

    private fun currentConnection(): Connection = TransactionManager.currentOrNull()
        ?.connection
        ?.connection as? Connection
        ?: error("voucher pool queries require an active VoucherPoolJdbcExecutor transaction")
}

private fun PreparedStatement.bindScope(tenantId: String, campaignId: UUID?, batchId: UUID?) {
    var index = 1
    setString(index++, tenantId)
    campaignId?.let { setObject(index++, it) }
    batchId?.let { setObject(index++, it) }
}

/** PostgreSQL transaction snapshot을 모든 DSL 조회의 observedAt 기준으로 고정합니다. */
private fun Connection.observedAt(): Instant =
    prepareStatement("SELECT transaction_timestamp()").use { statement ->
        statement.executeQuery().use { result ->
            result.next()
            result.getTimestamp(1).toInstant()
        }
    }

private fun BatchState.nextAction(): String =
    when (this) {
        BatchState.STAGING         -> "CONTINUE_OR_ACTIVATE"
        BatchState.ACTIVE          -> "PAUSE_OR_REVOKE"
        BatchState.PAUSED          -> "RESUME_OR_REVOKE"
        BatchState.REVOKING,
        BatchState.EXPIRING,
                                   -> "WAIT_FOR_WORKER"
        BatchState.FAILED_RETRYABLE -> "RESUME_OR_REVOKE"
        BatchState.FAILED_TERMINAL -> "OPERATOR_REVIEW_REQUIRED"
        BatchState.REVOKED,
        BatchState.EXPIRED,
                                   -> "NONE"
    }
