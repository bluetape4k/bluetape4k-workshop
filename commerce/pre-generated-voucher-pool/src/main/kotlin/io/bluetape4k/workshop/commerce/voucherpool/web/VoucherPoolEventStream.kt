@file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMetrics
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolSseProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolStreamShutdown
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import jakarta.annotation.PreDestroy
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.OutputStream
import java.io.Serializable
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class VoucherPoolEventCursor(
    val revision: Long,
    val id: Long,
) : Serializable {
    init {
        require(revision >= 0 && id >= 0)
    }

    override fun toString(): String = "$revision:$id"

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MAX_CURSOR_LENGTH = 64

        fun parse(raw: String?): VoucherPoolEventCursor? {
            if (raw == null) return null
            val values = raw.takeIf {
                it.length in 3..MAX_CURSOR_LENGTH && it.all { character -> character == ':' || character.isDigit() }
            }?.split(':')?.takeIf { it.size == 2 }
            val revision = values?.get(0)?.toLongOrNull()
            val id = values?.get(1)?.toLongOrNull()
            if (revision == null || id == null) throw invalidEventCursor()
            return VoucherPoolEventCursor(revision, id)
        }
    }
}

internal data class VoucherPoolStreamResource(
    val resourceType: String,
    val resourceId: UUID,
    val campaignId: UUID,
    val batchId: UUID?,
    val state: String,
    val revision: Long,
    val expiresAt: Instant?,
    val nextAction: String,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class VoucherPoolSnapshotResponse(
    val scope: String,
    val campaignId: UUID?,
    val batchId: UUID?,
    val reservations: List<VoucherPoolStreamResource>,
    val allocations: List<VoucherPoolStreamResource>,
    val campaigns: List<VoucherPoolStreamResource>,
    val batches: List<VoucherPoolStreamResource>,
    val counts: Map<String, Long>,
    val truncated: Boolean,
    val observedAt: Instant,
    val requestId: String? = null,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class VoucherPoolAuditHttpEvent(
    val aggregateType: String,
    val aggregateId: UUID,
    val revision: Long,
    val reasonCode: String,
    val policyVersion: Long,
    val occurredAt: Instant,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal sealed interface VoucherPoolStreamScope : Serializable {
    val tenantId: String
}

internal data class CustomerStreamScope(
    override val tenantId: String,
    val principalId: String,
) : VoucherPoolStreamScope {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class OperatorStreamScope(
    override val tenantId: String,
    val campaignId: UUID?,
    val batchId: UUID?,
) : VoucherPoolStreamScope {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class VoucherPoolStreamInitial(
    val snapshot: VoucherPoolSnapshotResponse,
    val cursor: VoucherPoolEventCursor,
    val resetRequired: Boolean,
    val scanAfterId: Long = cursor.id,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class VoucherPoolStreamBatch(
    val snapshot: VoucherPoolSnapshotResponse,
    val events: List<VoucherPoolAuditEnvelope>,
    val scannedThroughId: Long = events.lastOrNull()?.cursor?.id ?: 0L,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class VoucherPoolAuditEnvelope(
    val cursor: VoucherPoolEventCursor,
    val event: VoucherPoolAuditHttpEvent,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

private data class CustomerAuditScan(
    val events: List<VoucherPoolAuditEnvelope>,
    val scannedThroughId: Long,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal interface VoucherPoolEventSource {
    fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse
    fun initial(scope: VoucherPoolStreamScope, cursor: VoucherPoolEventCursor?): VoucherPoolStreamInitial
    fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch
}

/** PostgreSQL-authoritative, code-free snapshot and audit reader using only the reserved SSE lane. */
@Component
internal class PostgresVoucherPoolEventSource(
    private val executor: VoucherPoolJdbcExecutor,
    private val digests: VoucherDigestService,
    private val mapper: ObjectMapper,
    properties: VoucherPoolProperties,
) : VoucherPoolEventSource {
    private val config = properties.sse

    override fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse =
        executor.sseTransaction { snapshotInTransaction(scope) }

    override fun initial(
        scope: VoucherPoolStreamScope,
        cursor: VoucherPoolEventCursor?,
    ): VoucherPoolStreamInitial = executor.sseTransaction {
        val snapshot = snapshotInTransaction(scope)
        val first = auditBoundary(scope, first = true)
        val last = auditBoundary(scope, first = false)
        val reset = validateCursor(scope, snapshot, cursor, first, last)
        val visibleCursor = last?.cursor ?: snapshot.fallbackCursor()
        val scanAfterId = cursor?.id ?: tenantAuditHighWatermark(scope.tenantId)
        VoucherPoolStreamInitial(snapshot, visibleCursor, reset, scanAfterId)
    }

    override fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch =
        executor.sseTransaction {
            val snapshot = snapshotInTransaction(scope)
            when (scope) {
                is CustomerStreamScope -> withCustomerDigests(scope) { customerScopes ->
                    val scan = customerAuditScan(scope.tenantId, customerScopes, afterId)
                    VoucherPoolStreamBatch(snapshot, scan.events, scan.scannedThroughId)
                }
                is OperatorStreamScope -> {
                    val events = boundedAudits(scope, afterId)
                    VoucherPoolStreamBatch(snapshot, events, events.lastOrNull()?.cursor?.id ?: afterId)
                }
            }
        }

    private fun snapshotInTransaction(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse =
        when (scope) {
            is CustomerStreamScope -> customerSnapshot(scope)
            is OperatorStreamScope -> operatorSnapshot(scope)
        }

    private fun customerSnapshot(scope: CustomerStreamScope): VoucherPoolSnapshotResponse =
        withCustomerDigests(scope) { customerScopes ->
            val resources = readCustomerResources(scope.tenantId, customerScopes)
            val visible = resources.take(config.maxPollRows)
            VoucherPoolSnapshotResponse(
                scope = "CUSTOMER",
                campaignId = null,
                batchId = null,
                reservations = visible.filter { it.resourceType == "RESERVATION" },
                allocations = visible.filter { it.resourceType == "ALLOCATION" },
                campaigns = emptyList(),
                batches = emptyList(),
                counts = visible.groupingBy(VoucherPoolStreamResource::state).eachCount()
                    .mapValues { (_, count) -> count.toLong() },
                truncated = resources.size > config.maxPollRows,
                observedAt = currentConnection().observedAt(),
            )
        }

    private fun operatorSnapshot(scope: OperatorStreamScope): VoucherPoolSnapshotResponse {
        val resolved = resolveOperatorScope(scope)
        val campaigns = readOperatorCampaigns(scope.tenantId, resolved.campaignId)
        val batches = readOperatorBatches(scope.tenantId, resolved.campaignId, resolved.batchId)
        val counts = readOperatorCounts(scope.tenantId, resolved.campaignId, resolved.batchId)
        return VoucherPoolSnapshotResponse(
            scope = "OPERATOR",
            campaignId = resolved.campaignId,
            batchId = resolved.batchId,
            reservations = emptyList(),
            allocations = emptyList(),
            campaigns = campaigns,
            batches = batches,
            counts = counts,
            truncated = campaigns.size >= config.maxPollRows || batches.size >= config.maxPollRows,
            observedAt = currentConnection().observedAt(),
        )
    }

    private fun readCustomerResources(
        tenantId: String,
        scopes: List<CustomerCampaignDigest>,
    ): List<VoucherPoolStreamResource> {
        if (scopes.isEmpty()) return emptyList()
        val sql =
            """WITH owner_scope(campaign_id,user_digest) AS (${customerScopeValues(scopes)})
                SELECT resource_type,resource_id,campaign_id,batch_id,state,revision,expires_at
                FROM (
                    SELECT 'RESERVATION' AS resource_type,r.reservation_id AS resource_id,r.campaign_id,r.batch_id,
                           r.state,r.revision,r.reservation_expires_at AS expires_at
                    FROM voucher_pool_reservations r
                    JOIN owner_scope s ON s.campaign_id=r.campaign_id AND s.user_digest=r.user_digest
                    WHERE r.tenant_id=?
                    UNION ALL
                    SELECT 'ALLOCATION',a.allocation_id,a.campaign_id,a.batch_id,e.state,a.revision,a.allocation_expires_at
                    FROM voucher_pool_allocations a
                    JOIN owner_scope s ON s.campaign_id=a.campaign_id AND s.user_digest=a.user_digest
                    JOIN voucher_pool_entries e ON e.tenant_id=a.tenant_id AND e.entry_id=a.entry_id
                    WHERE a.tenant_id=?
                ) owned
                ORDER BY expires_at,resource_id LIMIT ?"""
        return currentConnection().prepareStatement(sql).use { statement ->
            var index = statement.bindCustomerScopes(scopes)
            statement.setString(index++, tenantId)
            statement.setString(index++, tenantId)
            statement.setInt(index, config.maxPollRows + 1)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val state = result.getString("state")
                        val type = result.getString("resource_type")
                        add(
                            VoucherPoolStreamResource(
                                type,
                                result.getObject("resource_id", UUID::class.java),
                                result.getObject("campaign_id", UUID::class.java),
                                result.getObject("batch_id", UUID::class.java),
                                state,
                                result.getLong("revision"),
                                result.getTimestamp("expires_at").toInstant(),
                                customerNextAction(type, state),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun readOperatorCampaigns(tenantId: String, campaignId: UUID?): List<VoucherPoolStreamResource> {
        val predicate = if (campaignId == null) "tenant_id=?" else "tenant_id=? AND campaign_id=?"
        return currentConnection().prepareStatement(
            """SELECT campaign_id,state,revision,ends_at FROM voucher_pool_campaigns
                WHERE $predicate ORDER BY campaign_id LIMIT ?""",
        ).use { statement ->
            var index = 1
            statement.setString(index++, tenantId)
            campaignId?.let { statement.setObject(index++, it) }
            statement.setInt(index, config.maxPollRows)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val id = result.getObject("campaign_id", UUID::class.java)
                        val state = result.getString("state")
                        add(
                            VoucherPoolStreamResource(
                                "CAMPAIGN", id, id, null, state, result.getLong("revision"),
                                result.getTimestamp("ends_at").toInstant(), campaignNextAction(state),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun readOperatorBatches(
        tenantId: String,
        campaignId: UUID?,
        batchId: UUID?,
    ): List<VoucherPoolStreamResource> {
        val clauses = mutableListOf("tenant_id=?")
        if (campaignId != null) clauses += "campaign_id=?"
        if (batchId != null) clauses += "batch_id=?"
        return currentConnection().prepareStatement(
            """SELECT batch_id,campaign_id,state,revision,expires_at FROM voucher_pool_batches
                WHERE ${clauses.joinToString(" AND ")} ORDER BY batch_id LIMIT ?""",
        ).use { statement ->
            var index = 1
            statement.setString(index++, tenantId)
            campaignId?.let { statement.setObject(index++, it) }
            batchId?.let { statement.setObject(index++, it) }
            statement.setInt(index, config.maxPollRows)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val state = result.getString("state")
                        add(
                            VoucherPoolStreamResource(
                                "BATCH",
                                result.getObject("batch_id", UUID::class.java),
                                result.getObject("campaign_id", UUID::class.java),
                                result.getObject("batch_id", UUID::class.java),
                                state,
                                result.getLong("revision"),
                                result.getTimestamp("expires_at")?.toInstant(),
                                batchNextAction(state),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun readOperatorCounts(tenantId: String, campaignId: UUID?, batchId: UUID?): Map<String, Long> {
        val clauses = mutableListOf("d.tenant_id=?")
        if (campaignId != null) clauses += "b.campaign_id=?"
        if (batchId != null) clauses += "d.batch_id=?"
        return currentConnection().prepareStatement(
            """SELECT d.state,sum(d.entry_count) FROM voucher_pool_pool_depth d
                JOIN voucher_pool_batches b ON b.tenant_id=d.tenant_id AND b.batch_id=d.batch_id
                WHERE ${clauses.joinToString(" AND ")} GROUP BY d.state""",
        ).use { statement ->
            var index = 1
            statement.setString(index++, tenantId)
            campaignId?.let { statement.setObject(index++, it) }
            batchId?.let { statement.setObject(index, it) }
            statement.executeQuery().use { result ->
                buildMap { while (result.next()) put(result.getString(1), result.getLong(2)) }
            }
        }
    }

    private fun resolveOperatorScope(scope: OperatorStreamScope): ResolvedOperatorScope =
        scope.batchId?.let { resolveBatchScope(scope, it) } ?: resolveCampaignScope(scope)

    private fun resolveBatchScope(scope: OperatorStreamScope, batchId: UUID): ResolvedOperatorScope {
        val actualCampaign = currentConnection().prepareStatement(
            "SELECT campaign_id FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=?",
        ).use { statement ->
            statement.setString(1, scope.tenantId)
            statement.setObject(2, batchId)
            statement.executeQuery().use { result ->
                if (result.next()) result.getObject(1, UUID::class.java) else null
            }
        } ?: throw resourceNotFound()
        if (scope.campaignId != null && scope.campaignId != actualCampaign) throw resourceNotFound()
        return ResolvedOperatorScope(actualCampaign, batchId)
    }

    private fun resolveCampaignScope(scope: OperatorStreamScope): ResolvedOperatorScope {
        val campaignId = scope.campaignId
        if (campaignId != null && !campaignExists(scope.tenantId, campaignId)) throw resourceNotFound()
        return ResolvedOperatorScope(campaignId, null)
    }

    private fun campaignExists(tenantId: String, campaignId: UUID): Boolean =
        currentConnection().prepareStatement(
            "SELECT 1 FROM voucher_pool_campaigns WHERE tenant_id=? AND campaign_id=?",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, campaignId)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun boundedAudits(scope: VoucherPoolStreamScope, afterId: Long): List<VoucherPoolAuditEnvelope> {
        val candidates = when (scope) {
            is OperatorStreamScope -> operatorAudits(scope, afterId, config.maxPollRows)
            is CustomerStreamScope -> withCustomerDigests(scope) { customerScopes ->
                customerAudits(scope.tenantId, customerScopes, afterId, config.maxPollRows)
            }
        }.sortedBy { it.cursor.id }
        val bounded = ArrayList<VoucherPoolAuditEnvelope>()
        var encodedBytes = 0
        for (candidate in candidates) {
            val size = mapper.writeValueAsBytes(candidate.event).size + candidate.cursor.toString().length + 32
            if (bounded.size >= config.maxPollRows || encodedBytes + size > config.maxPollBytes) break
            bounded += candidate
            encodedBytes += size
        }
        return bounded
    }

    private fun auditBoundary(scope: VoucherPoolStreamScope, first: Boolean): VoucherPoolAuditEnvelope? =
        when (scope) {
            is OperatorStreamScope -> operatorAudits(scope, if (first) -1 else 0, 1, descending = !first).firstOrNull()
            is CustomerStreamScope -> withCustomerDigests(scope) { customerScopes ->
                customerAudits(scope.tenantId, customerScopes, if (first) -1 else 0, 1, descending = !first)
                    .firstOrNull()
            }
        }

    private fun operatorAudits(
        scope: OperatorStreamScope,
        afterId: Long,
        limit: Int,
        descending: Boolean = false,
    ): List<VoucherPoolAuditEnvelope> {
        val resolved = resolveOperatorScope(scope)
        val clauses = mutableListOf("a.tenant_id=?")
        if (resolved.batchId != null) {
            clauses += """a.campaign_id=? AND (
                (a.aggregate_type='BATCH' AND a.aggregate_id=?) OR
                (a.aggregate_type='CAMPAIGN' AND a.aggregate_id=a.campaign_id) OR
                (a.aggregate_type='RECONCILIATION' AND a.aggregate_id=?) OR
                (a.aggregate_type='ENTRY' AND EXISTS (SELECT 1 FROM voucher_pool_entries e
                    WHERE e.tenant_id=a.tenant_id AND e.entry_id=a.aggregate_id AND e.batch_id=?)) OR
                (a.aggregate_type='RESERVATION' AND EXISTS (SELECT 1 FROM voucher_pool_reservations r
                    WHERE r.tenant_id=a.tenant_id AND r.reservation_id=a.aggregate_id AND r.batch_id=?)) OR
                (a.aggregate_type='ALLOCATION' AND EXISTS (SELECT 1 FROM voucher_pool_allocations x
                    WHERE x.tenant_id=a.tenant_id AND x.allocation_id=a.aggregate_id AND x.batch_id=?)))"""
        } else if (resolved.campaignId != null) {
            clauses += "a.campaign_id=?"
        }
        if (afterId >= 0) clauses += "a.id>?"
        val order = if (descending) "DESC" else "ASC"
        val sql = auditSelect("${clauses.joinToString(" AND ")} ORDER BY a.id $order LIMIT ?")
        return currentConnection().prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, scope.tenantId)
            resolved.campaignId?.let { statement.setObject(index++, it) }
            resolved.batchId?.let { batch -> repeat(5) { statement.setObject(index++, batch) } }
            if (afterId >= 0) statement.setLong(index++, afterId)
            statement.setInt(index, limit)
            statement.executeQuery().use(::readAudits)
        }
    }

    private fun customerAudits(
        tenantId: String,
        scopes: List<CustomerCampaignDigest>,
        afterId: Long,
        limit: Int,
        descending: Boolean = false,
    ): List<VoucherPoolAuditEnvelope> {
        if (scopes.isEmpty()) return emptyList()
        val after = if (afterId >= 0) "AND a.id>?" else ""
        val order = if (descending) "DESC" else "ASC"
        val sql = auditSelect(
            """a.tenant_id=? AND (
                (a.aggregate_type='RESERVATION' AND EXISTS (SELECT 1 FROM voucher_pool_reservations r
                    JOIN owner_scope s ON s.campaign_id=r.campaign_id AND s.user_digest=r.user_digest
                    WHERE r.tenant_id=a.tenant_id AND r.reservation_id=a.aggregate_id)) OR
                (a.aggregate_type='ALLOCATION' AND EXISTS (SELECT 1 FROM voucher_pool_allocations x
                    JOIN owner_scope s ON s.campaign_id=x.campaign_id AND s.user_digest=x.user_digest
                    WHERE x.tenant_id=a.tenant_id AND x.allocation_id=a.aggregate_id)))
                $after ORDER BY a.id $order LIMIT ?""",
            prefix = "WITH owner_scope(campaign_id,user_digest) AS (${customerScopeValues(scopes)}) ",
        )
        return currentConnection().prepareStatement(sql).use { statement ->
            var index = statement.bindCustomerScopes(scopes)
            statement.setString(index++, tenantId)
            if (afterId >= 0) statement.setLong(index++, afterId)
            statement.setInt(index, limit)
            statement.executeQuery().use(::readAudits)
        }
    }

    private fun customerAuditScan(
        tenantId: String,
        scopes: List<CustomerCampaignDigest>,
        afterId: Long,
    ): CustomerAuditScan {
        if (scopes.isEmpty()) return CustomerAuditScan(emptyList(), tenantAuditHighWatermark(tenantId))
        val sql =
            """WITH owner_scope(campaign_id,user_digest) AS (${customerScopeValues(scopes)}), raw_audits AS (
                    SELECT a.* FROM voucher_pool_audits a
                    WHERE a.tenant_id=? AND a.id>? ORDER BY a.id LIMIT ?
                ) SELECT a.*,
                    ((a.aggregate_type='RESERVATION' AND EXISTS (SELECT 1 FROM voucher_pool_reservations r
                        JOIN owner_scope s ON s.campaign_id=r.campaign_id AND s.user_digest=r.user_digest
                        WHERE r.tenant_id=a.tenant_id AND r.reservation_id=a.aggregate_id)) OR
                     (a.aggregate_type='ALLOCATION' AND EXISTS (SELECT 1 FROM voucher_pool_allocations x
                        JOIN owner_scope s ON s.campaign_id=x.campaign_id AND s.user_digest=x.user_digest
                        WHERE x.tenant_id=a.tenant_id AND x.allocation_id=a.aggregate_id))) AS visible
                FROM raw_audits a ORDER BY a.id"""
        return currentConnection().prepareStatement(sql).use { statement ->
            var index = statement.bindCustomerScopes(scopes)
            statement.setString(index++, tenantId)
            statement.setLong(index++, afterId)
            statement.setInt(index, config.maxPollRows)
            statement.executeQuery().use { result ->
                val events = ArrayList<VoucherPoolAuditEnvelope>()
                var scannedThroughId = afterId
                var encodedBytes = 0
                while (result.next()) {
                    val envelope = result.auditEnvelope()
                    if (result.getBoolean("visible")) {
                        val size = mapper.writeValueAsBytes(envelope.event).size +
                            envelope.cursor.toString().length + 32
                        if (events.size >= config.maxPollRows || encodedBytes + size > config.maxPollBytes) break
                        events += envelope
                        encodedBytes += size
                    }
                    scannedThroughId = envelope.cursor.id
                }
                CustomerAuditScan(events, scannedThroughId)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "ThrowsCount")
    private fun validateCursor(
        scope: VoucherPoolStreamScope,
        snapshot: VoucherPoolSnapshotResponse,
        requested: VoucherPoolEventCursor?,
        first: VoucherPoolAuditEnvelope?,
        last: VoucherPoolAuditEnvelope?,
    ): Boolean {
        if (requested == null) return false
        val maximumRevision = maxOf(snapshot.maximumRevision(), last?.cursor?.revision ?: 0)
        if (requested.revision > maximumRevision) throw invalidEventCursor()
        if (last == null) {
            if (requested.id > 0) throw invalidEventCursor()
            return false
        }
        if (requested.id > last.cursor.id) throw invalidEventCursor()
        if (requested.id == 0L) return first != null
        val referenced = findScopedAudit(scope, requested.id)
        if (referenced != null && referenced.cursor.revision != requested.revision) throw invalidEventCursor()
        if (referenced == null && auditExists(requested.id)) throw invalidEventCursor()
        if (referenced == null && (first == null || requested.id >= first.cursor.id)) throw invalidEventCursor()
        return referenced == null
    }

    private fun findScopedAudit(scope: VoucherPoolStreamScope, id: Long): VoucherPoolAuditEnvelope? =
        when (scope) {
            is OperatorStreamScope -> operatorAudits(scope, id - 1, 1).firstOrNull()?.takeIf { it.cursor.id == id }
            is CustomerStreamScope -> withCustomerDigests(scope) { customerScopes ->
                customerAudits(scope.tenantId, customerScopes, id - 1, 1).firstOrNull()
                    ?.takeIf { it.cursor.id == id }
            }
        }

    private fun auditExists(id: Long): Boolean = currentConnection().prepareStatement(
        "SELECT 1 FROM voucher_pool_audits WHERE id=?",
    ).use { statement ->
        statement.setLong(1, id)
        statement.executeQuery().use(ResultSet::next)
    }

    private fun tenantAuditHighWatermark(tenantId: String): Long = currentConnection().prepareStatement(
        "SELECT COALESCE(max(id),0) FROM voucher_pool_audits WHERE tenant_id=?",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.executeQuery().use { result -> result.next(); result.getLong(1) }
    }

    private fun <T> withCustomerDigests(scope: CustomerStreamScope, block: (List<CustomerCampaignDigest>) -> T): T {
        val campaigns = currentConnection().prepareStatement(
            """SELECT campaign_id,user_identity_key_version FROM voucher_pool_campaigns
                WHERE tenant_id=? ORDER BY campaign_id LIMIT ?""",
        ).use { statement ->
            statement.setString(1, scope.tenantId)
            statement.setInt(2, config.maxCustomerCampaigns + 1)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getObject(1, UUID::class.java) to result.getInt(2))
                }
            }
        }
        if (campaigns.size > config.maxCustomerCampaigns) {
            throw VoucherPoolApiException(
                "SNAPSHOT_SCOPE_TOO_LARGE",
                503,
                "snapshot scope is temporarily unavailable",
                1,
            )
        }
        val values = campaigns.map { (campaignId, keyVersion) ->
            val digest = digests.userIdentity(scope.tenantId, campaignId, scope.principalId, keyVersion).copyBytes()
            CustomerCampaignDigest(campaignId, digest)
        }
        return try {
            block(values)
        } finally {
            values.forEach { it.digest.fill(0) }
        }
    }

    private fun readAudits(result: ResultSet): List<VoucherPoolAuditEnvelope> =
        buildList { while (result.next()) add(result.auditEnvelope()) }

    private fun ResultSet.auditEnvelope(): VoucherPoolAuditEnvelope {
        val cursor = VoucherPoolEventCursor(getLong("revision"), getLong("id"))
        return VoucherPoolAuditEnvelope(
            cursor,
            VoucherPoolAuditHttpEvent(
                getString("aggregate_type"),
                getObject("aggregate_id", UUID::class.java),
                cursor.revision,
                getString("reason_code"),
                getLong("policy_version"),
                getTimestamp("created_at").toInstant(),
            ),
        )
    }

    private fun auditSelect(predicateAndOrder: String, prefix: String = ""): String =
        """${prefix}SELECT a.id,a.aggregate_type,a.aggregate_id,a.revision,a.reason_code,a.policy_version,a.created_at
            FROM voucher_pool_audits a WHERE $predicateAndOrder"""

    private fun customerScopeValues(scopes: List<CustomerCampaignDigest>): String =
        "VALUES " + scopes.joinToString(",") { "(?::uuid,?::bytea)" }

    private fun PreparedStatement.bindCustomerScopes(scopes: List<CustomerCampaignDigest>): Int {
        var index = 1
        scopes.forEach { scope ->
            setObject(index++, scope.campaignId)
            setBytes(index++, scope.digest)
        }
        return index
    }

    private fun currentConnection(): Connection = checkNotNull(TransactionManager.currentOrNull()) {
        "voucher pool event source requires an active JDBC transaction"
    }.connection.connection as Connection

    private class CustomerCampaignDigest(val campaignId: UUID, val digest: ByteArray)
    private class ResolvedOperatorScope(val campaignId: UUID?, val batchId: UUID?)
}

/** Shared bounded pollers keep JDBC permits outside subscriber queues and network writes. */
@Component
internal class VoucherPoolEventStream(
    private val source: VoucherPoolEventSource,
    private val mapper: ObjectMapper,
    private val executor: ExecutorService,
    properties: VoucherPoolProperties,
    private val metrics: VoucherPoolMetrics,
) : AutoCloseable, VoucherPoolStreamShutdown {
    private val config: VoucherPoolSseProperties = properties.sse
    private val registryLock = ReentrantLock()
    private val pollers = HashMap<VoucherPoolStreamScope, ScopePoller>()
    private val activeSubscribers = AtomicInteger()
    private val closed = AtomicBoolean()

    fun customerSnapshot(tenantId: String, principalId: String, requestId: String): VoucherPoolSnapshotResponse =
        source.snapshot(CustomerStreamScope(tenantId, principalId)).copy(requestId = requestId)

    fun operatorSnapshot(
        tenantId: String,
        campaignId: UUID?,
        batchId: UUID?,
        requestId: String,
    ): VoucherPoolSnapshotResponse =
        source.snapshot(OperatorStreamScope(tenantId, campaignId, batchId)).copy(requestId = requestId)

    fun openCustomer(
        tenantId: String,
        principalId: String,
        cursor: VoucherPoolEventCursor?,
    ): StreamSubscription = open(CustomerStreamScope(tenantId, principalId), cursor)

    fun openOperator(
        tenantId: String,
        campaignId: UUID?,
        batchId: UUID?,
        cursor: VoucherPoolEventCursor?,
    ): StreamSubscription = open(OperatorStreamScope(tenantId, campaignId, batchId), cursor)

    fun write(subscription: StreamSubscription, output: OutputStream) {
        try {
            var streaming = true
            while (streaming && !subscription.isClosed()) {
                val next = subscription.next(config.heartbeatInterval)
                streaming = next != null && !next.terminal
                if (next != null) writeWithDeadline(output, next)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: TimeoutException) {
            log.warn { "voucher_pool_sse_disconnected reason=write_timeout" }
        } catch (_: ExecutionException) {
            log.info { "voucher_pool_sse_disconnected reason=write_failure" }
        } catch (_: IOException) {
            log.info { "voucher_pool_sse_disconnected reason=client_close" }
        } finally {
            subscription.close()
        }
    }

    internal fun activePollers(): Int = registryLock.withLock { pollers.size }

    override fun closeSseAndPoller() = close()

    @PreDestroy
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = registryLock.withLock { pollers.values.toList().also { pollers.clear() } }
        active.forEach(ScopePoller::stop)
        metrics.sseSubscribers(0)
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private fun open(scope: VoucherPoolStreamScope, cursor: VoucherPoolEventCursor?): StreamSubscription {
        reserveSubscriber(scope)
        val initial = try {
            source.initial(scope, cursor)
        } catch (failure: RuntimeException) {
            releaseSubscriber()
            throw failure
        }
        val subscription = StreamSubscription(initial.cursor)
        subscription.offer(event(initial.cursor, "snapshot", initial.snapshot))
        if (initial.resetRequired) {
            val reset = event(initial.cursor, "reset", initial.snapshot, terminal = true)
            if (!subscription.offer(reset)) subscription.forceTerminal(reset)
            metrics.sseReset()
            return subscription
        }
        try {
            val registration = registryLock.withLock {
                val existing = pollers[scope]
                val poller = existing ?: ScopePoller(
                    scope,
                    initial.cursor,
                    initial.snapshot,
                    initial.scanAfterId,
                ).also {
                    pollers[scope] = it
                }
                if (poller.subscriberCount() >= config.maxSubscribersPerScope) throw sseCapacityRejected()
                poller.attach(subscription)
                PollerRegistration(poller, existing == null)
            }
            registration.poller.activate(subscription, initial.cursor)
            if (registration.created) registration.poller.start()
        } catch (failure: RuntimeException) {
            subscription.close()
            throw failure
        }
        return subscription
    }

    private fun reserveSubscriber(scope: VoucherPoolStreamScope) {
        registryLock.withLock {
            if (closed.get()) throw serviceShuttingDown()
            val globalCapacityReached = activeSubscribers.get() >= config.maxSubscribers
            val scopeCapacityReached = (pollers[scope]?.subscriberCount() ?: 0) >= config.maxSubscribersPerScope
            if (globalCapacityReached || scopeCapacityReached) throw sseCapacityRejected()
            val count = activeSubscribers.incrementAndGet()
            metrics.sseSubscribers(count)
        }
    }

    private fun releaseSubscriber() {
        val count = activeSubscribers.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        metrics.sseSubscribers(count)
    }

    private fun remove(subscription: StreamSubscription) {
        val stopped = registryLock.withLock {
            val poller = subscription.poller.getAndSet(null) ?: return@withLock null
            poller.detach(subscription)
            poller.takeIf { it.subscriberCount() == 0 && pollers.remove(it.scope, it) }
        }
        stopped?.stop()
        releaseSubscriber()
    }

    private fun event(
        cursor: VoucherPoolEventCursor,
        type: String,
        payload: Any,
        terminal: Boolean = false,
    ): VoucherPoolStreamEvent = VoucherPoolStreamEvent(cursor, type, mapper.writeValueAsString(payload), terminal)

    private fun writeWithDeadline(output: OutputStream, event: VoucherPoolStreamEvent) {
        val bytes = event.encode()
        val write = executor.submit { output.write(bytes); output.flush() }
        try {
            write.get(config.writeTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (failure: TimeoutException) {
            write.cancel(true)
            throw failure
        }
    }

    internal inner class StreamSubscription(initialCursor: VoucherPoolEventCursor) : AutoCloseable {
        private val queue = ArrayBlockingQueue<VoucherPoolStreamEvent>(config.queueSize)
        private val queuedBytes = AtomicInteger()
        private val cursor = AtomicReference(initialCursor)
        private val closed = AtomicBoolean()
        private val cleanupCount = AtomicLong()
        private val live = AtomicBoolean()
        internal val poller = AtomicReference<ScopePoller?>()

        internal fun offer(next: VoucherPoolStreamEvent): Boolean = when {
            closed.get() -> false
            next.type == "audit" && next.cursor.id <= cursor.get().id -> true
            else -> enqueue(next)
        }

        private fun enqueue(next: VoucherPoolStreamEvent): Boolean {
            val size = next.encodedSize()
            val withinByteLimit = size <= config.maxQueueBytes && queuedBytes.get() + size <= config.maxQueueBytes
            val accepted = withinByteLimit && queue.offer(next)
            if (accepted) {
                queuedBytes.addAndGet(size)
                cursor.set(next.cursor)
            }
            return accepted
        }

        internal fun forceTerminal(next: VoucherPoolStreamEvent) {
            queue.clear()
            queuedBytes.set(0)
            queue.offer(next)
            queuedBytes.set(next.encodedSize())
            cursor.set(next.cursor)
        }

        internal fun overflow(snapshot: VoucherPoolSnapshotResponse) {
            if (closed.get()) return
            forceTerminal(event(cursor.get(), "reset", snapshot, terminal = true))
            metrics.sseReset()
        }

        internal fun terminate(code: String) {
            if (closed.get()) return
            forceTerminal(event(cursor.get(), "error", mapOf("code" to code), terminal = true))
        }

        internal fun next(timeout: Duration): VoucherPoolStreamEvent? {
            return if (closed.get() && queue.isEmpty()) {
                null
            } else {
                queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)?.also { value ->
                    queuedBytes.addAndGet(-value.encodedSize())
                } ?: event(cursor.get(), "heartbeat", mapOf("observedAt" to Instant.now()))
            }
        }

        internal fun attach(owner: ScopePoller) {
            check(poller.compareAndSet(null, owner))
        }

        internal fun activate() {
            live.set(true)
        }

        internal fun isLive(): Boolean = live.get()

        internal fun isClosed(): Boolean = closed.get()
        internal fun queueDepth(): Int = queue.size
        internal fun cleanupInvocationCount(): Long = cleanupCount.get()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            cleanupCount.incrementAndGet()
            queue.clear()
            queuedBytes.set(0)
            remove(this)
        }
    }

    internal inner class ScopePoller(
        val scope: VoucherPoolStreamScope,
        initialCursor: VoucherPoolEventCursor,
        initialSnapshot: VoucherPoolSnapshotResponse,
        initialScanAfterId: Long = initialCursor.id,
    ) {
        private val subscribers = ConcurrentHashMap.newKeySet<StreamSubscription>()
        private val cursor = AtomicReference(initialCursor)
        private val scanAfterId = AtomicLong(initialScanAfterId)
        private val snapshot = AtomicReference(initialSnapshot.normalized())
        private val running = AtomicBoolean(true)
        private val task = AtomicReference<Future<*>?>()
        private val delay = AtomicReference(config.pollInterval)
        private val cycleLock = ReentrantLock()

        fun start() {
            task.set(executor.submit(::run))
        }

        fun attach(subscription: StreamSubscription) {
            subscription.attach(this)
            subscribers += subscription
        }

        fun detach(subscription: StreamSubscription) {
            subscribers -= subscription
        }

        fun activate(subscription: StreamSubscription, initialCursor: VoucherPoolEventCursor) {
            cycleLock.withLock {
                check(running.get()) { "SSE poller is stopped" }
                val target = cursor.get()
                if (target.id > initialCursor.id) {
                    val catchup = source.poll(scope, initialCursor.id)
                    if (catchup.scannedThroughId < target.id) {
                        subscription.overflow(catchup.snapshot)
                        return
                    }
                    catchup.events.forEach { event ->
                        val next = this@VoucherPoolEventStream.event(event.cursor, "audit", event.event)
                        if (!subscription.offer(next)) {
                            subscription.overflow(catchup.snapshot)
                            return
                        }
                    }
                }
                subscription.activate()
            }
        }

        fun subscriberCount(): Int = subscribers.size

        fun stop() {
            if (!running.compareAndSet(true, false)) return
            task.getAndSet(null)?.cancel(true)
            subscribers.toList().forEach(StreamSubscription::close)
            subscribers.clear()
        }

        @Suppress("TooGenericExceptionCaught")
        private fun run() {
            while (running.get() && sleep(delay.get())) {
                try {
                    cycleLock.withLock {
                        if (!running.get()) return
                        val batch = source.poll(scope, scanAfterId.get())
                        process(batch)
                    }
                } catch (failure: VoucherPoolApiException) {
                    subscribers.toList().forEach { it.terminate(failure.stableCode) }
                    stop()
                } catch (failure: RuntimeException) {
                    if (running.get()) {
                        log.warn { "voucher_pool_sse_poll_failed failure=${failure.javaClass.simpleName}" }
                    }
                    delay.set(config.maxIdleInterval)
                }
            }
        }

        private fun process(batch: VoucherPoolStreamBatch) {
            scanAfterId.updateAndGet { current -> maxOf(current, batch.scannedThroughId) }
            val normalizedSnapshot = batch.snapshot.normalized()
            if (snapshot.getAndSet(normalizedSnapshot) != normalizedSnapshot) {
                broadcast(event(cursor.get(), "snapshot", batch.snapshot), batch.snapshot)
            }
            if (batch.events.isEmpty()) {
                delay.updateAndGet { it.multipliedBy(2).coerceAtMost(config.maxIdleInterval) }
                return
            }
            delay.set(config.pollInterval)
            batch.events.forEach { envelope ->
                cursor.set(envelope.cursor)
                broadcast(event(envelope.cursor, "audit", envelope.event), batch.snapshot)
            }
        }

        private fun broadcast(next: VoucherPoolStreamEvent, currentSnapshot: VoucherPoolSnapshotResponse) {
            subscribers.toList().filter(StreamSubscription::isLive).forEach { subscriber ->
                if (!subscriber.offer(next)) subscriber.overflow(currentSnapshot)
            }
        }

        private fun sleep(duration: Duration): Boolean = try {
            TimeUnit.NANOSECONDS.sleep(duration.toNanos())
            true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun VoucherPoolSnapshotResponse.normalized(): VoucherPoolSnapshotResponse =
        copy(observedAt = Instant.EPOCH, requestId = null)

    companion object : KLogging()

    private class PollerRegistration(
        val poller: ScopePoller,
        val created: Boolean,
    )
}

internal data class VoucherPoolStreamEvent(
    val cursor: VoucherPoolEventCursor,
    val type: String,
    val data: String,
    val terminal: Boolean = false,
) : Serializable {
    private val encoded = buildString {
        append("id: ").append(cursor).append('\n')
        append("event: ").append(type).append('\n')
        append("data: ").append(data.replace("\n", "")).append("\n\n")
    }.toByteArray(UTF_8)

    fun encode(): ByteArray = encoded

    fun encodedSize(): Int = encoded.size

    companion object { private const val serialVersionUID: Long = 1L }
}

private fun VoucherPoolSnapshotResponse.maximumRevision(): Long =
    (reservations + allocations + campaigns + batches).maxOfOrNull(VoucherPoolStreamResource::revision) ?: 0

private fun VoucherPoolSnapshotResponse.fallbackCursor(): VoucherPoolEventCursor =
    VoucherPoolEventCursor(maximumRevision(), 0)

private fun Connection.observedAt(): Instant = createStatement().use { statement ->
    statement.executeQuery("SELECT transaction_timestamp()").use { result ->
        result.next()
        result.getTimestamp(1).toInstant()
    }
}

private fun customerNextAction(type: String, state: String): String = when (type to state) {
    "RESERVATION" to "ACTIVE" -> "ALLOCATE_OR_RELEASE"
    "ALLOCATION" to "ALLOCATED" -> "REVEAL_REDEEM_OR_RELEASE"
    else -> "REFRESH_SNAPSHOT"
}

private fun campaignNextAction(state: String): String = when (state) {
    "DRAFT" -> "UPDATE_POLICY_OR_ACTIVATE"
    "ACTIVE" -> "PAUSE_OR_REVOKE_PREVIEW"
    "PAUSED" -> "RESUME_OR_REVOKE_PREVIEW"
    else -> "REVIEW_CAMPAIGN"
}

private fun batchNextAction(state: String): String = when (state) {
    "STAGING" -> "IMPORT_CHUNK_OR_ACTIVATE"
    "ACTIVE" -> "PAUSE_OR_REVOKE_PREVIEW"
    "PAUSED" -> "RESUME_OR_REVOKE_PREVIEW"
    "FAILED_RETRYABLE" -> "RESUME"
    else -> "REVIEW_BATCH"
}

private fun invalidEventCursor(): VoucherPoolApiException =
    VoucherPoolApiException("INVALID_EVENT_CURSOR", 400, "event cursor is invalid")

private fun sseCapacityRejected(): VoucherPoolApiException =
    VoucherPoolApiException("SSE_CAPACITY_REJECTED", 503, "event stream capacity is unavailable", 1)

private fun serviceShuttingDown(): VoucherPoolApiException =
    VoucherPoolApiException("SERVICE_SHUTTING_DOWN", 503, "service is shutting down", 1)

internal fun resolveEventCursor(queryCursor: String?, lastEventId: String?): VoucherPoolEventCursor? {
    if (queryCursor != null && lastEventId != null && queryCursor != lastEventId) throw invalidEventCursor()
    return VoucherPoolEventCursor.parse(queryCursor ?: lastEventId)
}
