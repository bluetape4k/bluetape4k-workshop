@file:Suppress("LongParameterList", "MagicNumber", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.query

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.io.Serializable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

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
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
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
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
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
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
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
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class PoolDepthReadModel(
    val campaignId: UUID?,
    val batchId: UUID?,
    val counts: Map<EntryState, Long>,
    val eligibleAvailable: Long,
    val expiredButNotTerminalized: Long,
    val observedAt: Instant,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class StuckReservationCursor(
    val expiresAt: Instant,
    val reservationId: UUID,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class StuckReservationReadModel(
    val reservationId: UUID,
    val campaignId: UUID,
    val batchId: UUID,
    val entryId: UUID,
    val state: ReservationState,
    val expiresAt: Instant,
    val revision: Long,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class StuckReservationPage(
    val items: List<StuckReservationReadModel>,
    val nextCursor: StuckReservationCursor?,
    val observedAt: Instant,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
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
) : VoucherPoolQueryService {
    override fun reservation(tenantId: String, principalId: String, reservationId: UUID): ReservationReadModel? =
        executor.foregroundTransaction {
            val campaignId =
                store.resolveReservationCampaign(tenantId, reservationId)
                    ?: return@foregroundTransaction null
            withUserDigest(tenantId, campaignId, principalId) { ownerDigest ->
                store.findOwnedReservation(tenantId, reservationId, ownerDigest)
            }
        }

    override fun allocation(tenantId: String, principalId: String, allocationId: UUID): AllocationReadModel? =
        executor.foregroundTransaction {
            val campaignId =
                store.resolveAllocationCampaign(tenantId, allocationId)
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
        require(limit in 1..MAX_PAGE_SIZE) { "stuck reservation limit must be between 1 and $MAX_PAGE_SIZE" }
        return executor.operatorTransaction {
            if (!store.scopeExists(tenantId, campaignId, null)) return@operatorTransaction null
            store.findStuckReservations(tenantId, campaignId, cursor, limit)
        }
    }

    private fun <T> withUserDigest(
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

    private companion object {
        const val MAX_PAGE_SIZE = 100
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

internal class JdbcVoucherPoolQueryStore : VoucherPoolQueryStore {
    override fun findUserIdentityKeyVersion(tenantId: String, campaignId: UUID): Int? =
        currentConnection().prepareStatement(
            "SELECT user_identity_key_version FROM voucher_pool_campaigns WHERE tenant_id=? AND campaign_id=?",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, campaignId)
            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else null }
        }

    override fun scopeExists(tenantId: String, campaignId: UUID?, batchId: UUID?): Boolean {
        if (campaignId == null && batchId == null) return true
        val (sql, id) =
            when {
                campaignId != null && batchId != null ->
                    "SELECT 1 FROM voucher_pool_batches WHERE tenant_id=? AND campaign_id=? AND batch_id=?" to null
                campaignId != null ->
                    "SELECT 1 FROM voucher_pool_campaigns WHERE tenant_id=? AND campaign_id=?" to campaignId
                else ->
                    "SELECT 1 FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=?" to checkNotNull(batchId)
            }
        return currentConnection().prepareStatement(sql).use { statement ->
            statement.setString(1, tenantId)
            if (campaignId != null && batchId != null) {
                statement.setObject(2, campaignId)
                statement.setObject(3, batchId)
            } else {
                statement.setObject(2, id)
            }
            statement.executeQuery().use(ResultSet::next)
        }
    }

    override fun resolveReservationCampaign(tenantId: String, reservationId: UUID): UUID? =
        resolveCampaign("voucher_pool_reservations", "reservation_id", tenantId, reservationId)

    override fun findOwnedReservation(
        tenantId: String,
        reservationId: UUID,
        ownerDigest: ByteArray,
    ): ReservationReadModel? = currentConnection().prepareStatement(
        """SELECT reservation_id,campaign_id,batch_id,entry_id,state,reservation_expires_at,
                  entitlement_root_id,replacement_ordinal,policy_version,revision,transaction_timestamp()
            FROM voucher_pool_reservations
            WHERE tenant_id=? AND reservation_id=? AND user_digest=?""",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setObject(2, reservationId)
        statement.setBytes(3, ownerDigest)
        statement.executeQuery().use { result -> if (result.next()) result.reservationReadModel() else null }
    }

    override fun resolveAllocationCampaign(tenantId: String, allocationId: UUID): UUID? =
        resolveCampaign("voucher_pool_allocations", "allocation_id", tenantId, allocationId)

    override fun findOwnedAllocation(
        tenantId: String,
        allocationId: UUID,
        ownerDigest: ByteArray,
    ): AllocationReadModel? = currentConnection().prepareStatement(
        """SELECT a.allocation_id,a.reservation_id,a.campaign_id,a.batch_id,a.entry_id,e.state,
                  a.allocation_expires_at,a.entitlement_root_id,a.replacement_ordinal,a.policy_version,
                  a.revision,transaction_timestamp()
            FROM voucher_pool_allocations a
            JOIN voucher_pool_entries e
              ON e.tenant_id=a.tenant_id AND e.entry_id=a.entry_id
            WHERE a.tenant_id=? AND a.allocation_id=? AND a.user_digest=?""",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setObject(2, allocationId)
        statement.setBytes(3, ownerDigest)
        statement.executeQuery().use { result -> if (result.next()) result.allocationReadModel() else null }
    }

    override fun findBatch(tenantId: String, batchId: UUID): BatchReadModel? =
        currentConnection().prepareStatement(
            """SELECT batch_id,campaign_id,state,source_kind,activates_at,expires_at,next_source_ordinal,
                      expected_count,accepted_count,rejected_count,last_failure_code,revision,transaction_timestamp()
                FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=?""",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, batchId)
            statement.executeQuery().use { result -> if (result.next()) result.batchReadModel() else null }
        }

    override fun findCampaign(tenantId: String, campaignId: UUID): CampaignReadModel? =
        currentConnection().prepareStatement(
            """SELECT campaign_id,state,starts_at,ends_at,per_user_limit,reservation_ttl_seconds,
                      allocation_ttl_seconds,replacement_allowance,policy_version,revision,transaction_timestamp()
                FROM voucher_pool_campaigns WHERE tenant_id=? AND campaign_id=?""",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, campaignId)
            statement.executeQuery().use { result -> if (result.next()) result.campaignReadModel() else null }
        }

    @Suppress("LongMethod", "NestedBlockDepth") // Keep the one-statement MVCC snapshot and its parser adjacent.
    override fun readPoolDepth(tenantId: String, campaignId: UUID?, batchId: UUID?): PoolDepthReadModel {
        val connection = currentConnection()
        val clauses = mutableListOf("b.tenant_id=?")
        if (campaignId != null) clauses += "b.campaign_id=?"
        if (batchId != null) clauses += "b.batch_id=?"
        val sql =
            """WITH scoped_batches AS (
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
                ORDER BY r.state"""
        return connection.prepareStatement(sql).use { statement ->
            statement.bindScope(tenantId, campaignId, batchId)
            statement.executeQuery().use { result ->
                val counts = mutableMapOf<EntryState, Long>()
                var eligibleAvailable = 0L
                var expiredButNotTerminalized = 0L
                var observedAt: Instant? = null
                while (result.next()) {
                    result.getString(1)?.let { state -> counts[EntryState.valueOf(state)] = result.getLong(2) }
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
        val connection = currentConnection()
        val observedAt = connection.observedAt()
        val clauses = mutableListOf("tenant_id=?", "state='ACTIVE'", "reservation_expires_at<=?")
        if (campaignId != null) clauses += "campaign_id=?"
        if (cursor != null) clauses += "(reservation_expires_at,reservation_id)>(?,?)"
        val sql =
            """SELECT reservation_id,campaign_id,batch_id,entry_id,state,reservation_expires_at,revision
                FROM voucher_pool_reservations
                WHERE ${clauses.joinToString(" AND ")}
                ORDER BY reservation_expires_at,reservation_id
                LIMIT ?"""
        val rows = connection.prepareStatement(sql).use { statement ->
            statement.bindStuckQuery(tenantId, campaignId, cursor, observedAt, limit + 1)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.stuckReservationReadModel())
                }
            }
        }
        val items = rows.take(limit)
        val nextCursor =
            if (rows.size > limit) {
                items.last().let { StuckReservationCursor(it.expiresAt, it.reservationId) }
            } else {
                null
            }
        return StuckReservationPage(items, nextCursor, observedAt)
    }

    private fun resolveCampaign(table: String, idColumn: String, tenantId: String, resourceId: UUID): UUID? {
        require(table to idColumn in RESOURCE_LOOKUPS)
        return currentConnection().prepareStatement(
            "SELECT campaign_id FROM $table WHERE tenant_id=? AND $idColumn=?",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, resourceId)
            statement.executeQuery().use { result ->
                if (result.next()) result.getObject(1, UUID::class.java) else null
            }
        }
    }

    private fun currentConnection(): Connection = checkNotNull(TransactionManager.currentOrNull()) {
        "voucher pool queries require an active VoucherPoolJdbcExecutor transaction"
    }.connection.connection as Connection

    private companion object {
        val RESOURCE_LOOKUPS =
            setOf(
                "voucher_pool_reservations" to "reservation_id",
                "voucher_pool_allocations" to "allocation_id",
            )
    }
}

private fun PreparedStatement.bindScope(tenantId: String, campaignId: UUID?, batchId: UUID?) {
    var index = 1
    setString(index++, tenantId)
    campaignId?.let { setObject(index++, it) }
    batchId?.let { setObject(index++, it) }
}

private fun Connection.observedAt(): Instant = prepareStatement("SELECT transaction_timestamp()").use { statement ->
    statement.executeQuery().use { result -> result.next(); result.getTimestamp(1).toInstant() }
}

private fun PreparedStatement.bindStuckQuery(
    tenantId: String,
    campaignId: UUID?,
    cursor: StuckReservationCursor?,
    observedAt: Instant,
    limit: Int,
) {
    var index = 1
    setString(index++, tenantId)
    setTimestamp(index++, Timestamp.from(observedAt))
    campaignId?.let { setObject(index++, it) }
    cursor?.let {
        setTimestamp(index++, Timestamp.from(it.expiresAt))
        setObject(index++, it.reservationId)
    }
    setInt(index, limit)
}

private fun ResultSet.reservationReadModel() =
    ReservationReadModel(
        reservationId = getObject(1, UUID::class.java),
        campaignId = getObject(2, UUID::class.java),
        batchId = getObject(3, UUID::class.java),
        entryId = getObject(4, UUID::class.java),
        state = ReservationState.valueOf(getString(5)),
        expiresAt = getTimestamp(6).toInstant(),
        entitlementRootId = getObject(7, UUID::class.java),
        replacementOrdinal = getInt(8),
        policyVersion = getLong(9),
        revision = getLong(10),
        observedAt = getTimestamp(11).toInstant(),
    )

private fun ResultSet.allocationReadModel() =
    AllocationReadModel(
        allocationId = getObject(1, UUID::class.java),
        reservationId = getObject(2, UUID::class.java),
        campaignId = getObject(3, UUID::class.java),
        batchId = getObject(4, UUID::class.java),
        entryId = getObject(5, UUID::class.java),
        state = EntryState.valueOf(getString(6)),
        expiresAt = getTimestamp(7).toInstant(),
        entitlementRootId = getObject(8, UUID::class.java),
        replacementOrdinal = getInt(9),
        policyVersion = getLong(10),
        revision = getLong(11),
        observedAt = getTimestamp(12).toInstant(),
    )

private fun ResultSet.batchReadModel(): BatchReadModel {
    val state = BatchState.valueOf(getString(3))
    return BatchReadModel(
        batchId = getObject(1, UUID::class.java),
        campaignId = getObject(2, UUID::class.java),
        state = state,
        sourceKind = getString(4),
        activatesAt = getTimestamp(5).toInstant(),
        expiresAt = getTimestamp(6)?.toInstant(),
        nextSourceOrdinal = getLong(7),
        expectedCount = getLong(8),
        acceptedCount = getLong(9),
        rejectedCount = getLong(10),
        lastFailureCode = getString(11),
        revision = getLong(12),
        nextAction = state.nextAction(),
        observedAt = getTimestamp(13).toInstant(),
    )
}

private fun ResultSet.campaignReadModel() =
    CampaignReadModel(
        campaignId = getObject(1, UUID::class.java),
        state = CampaignState.valueOf(getString(2)),
        startsAt = getTimestamp(3).toInstant(),
        endsAt = getTimestamp(4).toInstant(),
        perUserLimit = getInt(5),
        reservationTtlSeconds = getLong(6),
        allocationTtlSeconds = getLong(7),
        replacementAllowance = getInt(8),
        policyVersion = getLong(9),
        revision = getLong(10),
        observedAt = getTimestamp(11).toInstant(),
    )

private fun ResultSet.stuckReservationReadModel() =
    StuckReservationReadModel(
        reservationId = getObject(1, UUID::class.java),
        campaignId = getObject(2, UUID::class.java),
        batchId = getObject(3, UUID::class.java),
        entryId = getObject(4, UUID::class.java),
        state = ReservationState.valueOf(getString(5)),
        expiresAt = getTimestamp(6).toInstant(),
        revision = getLong(7),
    )

private fun BatchState.nextAction(): String =
    when (this) {
        BatchState.STAGING -> "CONTINUE_OR_ACTIVATE"
        BatchState.ACTIVE -> "PAUSE_OR_REVOKE"
        BatchState.PAUSED -> "RESUME_OR_REVOKE"
        BatchState.REVOKING,
        BatchState.EXPIRING,
        -> "WAIT_FOR_WORKER"
        BatchState.FAILED_RETRYABLE -> "RESUME_OR_REVOKE"
        BatchState.FAILED_TERMINAL -> "OPERATOR_REVIEW_REQUIRED"
        BatchState.REVOKED,
        BatchState.EXPIRED,
        -> "NONE"
    }
