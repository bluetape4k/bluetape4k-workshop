@file:Suppress("LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions", "UnusedParameter")

package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal interface VoucherPoolRepository {
    fun createCampaign(connection: Connection, campaign: CampaignRecord): CampaignRecord
    fun lockCampaignForUpdate(connection: Connection, tenantId: String, campaignId: UUID): CampaignRecord?
    fun updateCampaign(connection: Connection, campaign: CampaignRecord, expectedRevision: Long): CampaignRecord
    fun createBatch(connection: Connection, batch: BatchRecord): BatchRecord
    fun lockBatchForUpdate(connection: Connection, tenantId: String, batchId: UUID): BatchRecord?
    fun insertPreparedEntries(connection: Connection, entries: List<PreparedVoucherEntryRecord>)
    fun committedOrdinalDigests(
        connection: Connection,
        tenantId: String,
        batchId: UUID,
        firstOrdinal: Long,
        count: Int,
    ): List<CommittedOrdinalDigest>
    fun updateBatchCheckpoint(
        connection: Connection,
        batch: BatchRecord,
        nextSourceOrdinal: Long,
        acceptedCount: Long,
        rejectedCount: Long,
        checkpointDigest: DigestValue,
        state: BatchState,
        lastFailureCode: String?,
    ): BatchRecord
    fun markBatchTerminalFailure(
        connection: Connection,
        batch: BatchRecord,
        rejectedCount: Long,
        failureCode: String,
    ): BatchRecord
    fun batchOrdinalCoverage(connection: Connection, tenantId: String, batchId: UUID): BatchOrdinalCoverage
    fun activateBatch(connection: Connection, batch: BatchRecord): BatchRecord
    fun lockCampaignForShare(connection: Connection, tenantId: String, campaignId: UUID): CampaignRecord
    fun lockBatchForShare(connection: Connection, tenantId: String, batchId: UUID): BatchRecord
    fun lockUserLimit(connection: Connection, tenantId: String, campaignId: UUID, userDigest: ByteArray): UserLimitRecord
    fun selectAvailableEntrySkipLocked(connection: Connection, tenantId: String, campaignId: UUID): EntryRecord?
    fun lockCanonicalChain(connection: Connection, candidate: WorkerCandidate): LockedWorkerChain?
    fun lockAllocatedCryptoEntry(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        entryId: UUID,
        sourceOrdinal: Long,
        expectedRevision: Long,
    ): LockedVoucherCryptoRecord?
    fun eraseVoucherCiphertext(connection: Connection, tenantId: String, entryId: UUID, expectedRevision: Long)
    fun quarantineVoucherCrypto(
        connection: Connection,
        tenantId: String,
        entryId: UUID,
        sourceState: EntryState,
        sourceRevision: Long,
        reasonCode: String,
    )
    fun appendAudit(connection: Connection, event: VoucherPoolAuditRecord)
}

/** PostgreSQL authority repository; raw JDBC is limited to explicit row-lock syntax. */
@Suppress("LargeClass")
internal class JdbcVoucherPoolRepository(private val dataSource: DataSource) : VoucherPoolRepository {
    private val database = Database.connect(dataSource)

    override fun createCampaign(connection: Connection, campaign: CampaignRecord): CampaignRecord {
        connection.prepareStatement(
            """INSERT INTO voucher_pool_campaigns
                (tenant_id,campaign_id,state,starts_at,ends_at,per_user_limit,reservation_ttl_seconds,
                 allocation_ttl_seconds,replacement_allowance,policy_version,revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        ).use { statement ->
            statement.setString(1, campaign.tenantId)
            statement.setObject(2, campaign.campaignId)
            statement.setString(3, campaign.state.name)
            statement.setTimestamp(4, Timestamp.from(campaign.startsAt))
            statement.setTimestamp(5, Timestamp.from(campaign.endsAt))
            statement.setInt(6, campaign.perUserLimit)
            statement.setLong(7, campaign.reservationTtlSeconds)
            statement.setLong(8, campaign.allocationTtlSeconds)
            statement.setInt(9, campaign.replacementAllowance)
            statement.setLong(10, campaign.policyVersion)
            statement.setLong(11, campaign.revision)
            statement.executeUpdate()
        }
        return checkNotNull(lockCampaignForUpdate(connection, campaign.tenantId, campaign.campaignId))
    }

    override fun lockCampaignForUpdate(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
    ): CampaignRecord? = connection.prepareStatement(
        "SELECT * FROM voucher_pool_campaigns WHERE tenant_id=? AND campaign_id=? FOR UPDATE",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setObject(2, campaignId)
        statement.executeQuery().use { result -> if (result.next()) result.campaignRecord() else null }
    }

    override fun updateCampaign(
        connection: Connection,
        campaign: CampaignRecord,
        expectedRevision: Long,
    ): CampaignRecord {
        val updated = connection.prepareStatement(
            """UPDATE voucher_pool_campaigns
                SET state=?,starts_at=?,ends_at=?,per_user_limit=?,reservation_ttl_seconds=?,
                    allocation_ttl_seconds=?,replacement_allowance=?,policy_version=?,revision=revision+1,
                    updated_at=statement_timestamp()
                WHERE tenant_id=? AND campaign_id=? AND revision=?""",
        ).use { statement ->
            statement.setString(1, campaign.state.name)
            statement.setTimestamp(2, Timestamp.from(campaign.startsAt))
            statement.setTimestamp(3, Timestamp.from(campaign.endsAt))
            statement.setInt(4, campaign.perUserLimit)
            statement.setLong(5, campaign.reservationTtlSeconds)
            statement.setLong(6, campaign.allocationTtlSeconds)
            statement.setInt(7, campaign.replacementAllowance)
            statement.setLong(8, campaign.policyVersion)
            statement.setString(9, campaign.tenantId)
            statement.setObject(10, campaign.campaignId)
            statement.setLong(11, expectedRevision)
            statement.executeUpdate()
        }
        check(updated == 1) { "campaign update lost its revision" }
        return checkNotNull(lockCampaignForUpdate(connection, campaign.tenantId, campaign.campaignId))
    }

    override fun createBatch(connection: Connection, batch: BatchRecord): BatchRecord {
        connection.prepareStatement(
            """INSERT INTO voucher_pool_batches
                (tenant_id,batch_id,campaign_id,state,source_kind,provenance_digest,request_fingerprint,
                 policy_version,activates_at,expires_at,next_source_ordinal,expected_count,accepted_count,
                 rejected_count,checkpoint_digest,last_failure_code,revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        ).use { statement ->
            statement.setString(1, batch.tenantId)
            statement.setObject(2, batch.batchId)
            statement.setObject(3, batch.campaignId)
            statement.setString(4, batch.state.name)
            statement.setString(5, batch.sourceKind)
            statement.setBytes(6, batch.provenanceDigest.copyBytes())
            statement.setBytes(7, batch.requestFingerprint.copyBytes())
            statement.setLong(8, batch.policyVersion)
            statement.setTimestamp(9, Timestamp.from(batch.activatesAt))
            statement.setTimestamp(10, batch.expiresAt?.let(Timestamp::from))
            statement.setLong(11, batch.nextSourceOrdinal)
            statement.setLong(12, batch.expectedCount)
            statement.setLong(13, batch.acceptedCount)
            statement.setLong(14, batch.rejectedCount)
            statement.setBytes(15, batch.checkpointDigest?.copyBytes())
            statement.setString(16, batch.lastFailureCode)
            statement.setLong(17, batch.revision)
            statement.executeUpdate()
        }
        return checkNotNull(lockBatchForUpdate(connection, batch.tenantId, batch.batchId))
    }

    override fun lockBatchForUpdate(connection: Connection, tenantId: String, batchId: UUID): BatchRecord? =
        connection.prepareStatement(
            "SELECT * FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, batchId)
            statement.executeQuery().use { result -> if (result.next()) result.batchRecord() else null }
        }

    override fun insertPreparedEntries(connection: Connection, entries: List<PreparedVoucherEntryRecord>) {
        entries.forEach { entry ->
            connection.prepareStatement(
                """INSERT INTO voucher_pool_code_dedup
                    (tenant_id,stable_dedup_digest,first_campaign_id,first_batch_id,first_entry_id,key_version)
                    VALUES (?,?,?,?,?,?)""",
            ).use { statement ->
                statement.setString(1, entry.tenantId)
                statement.setBytes(2, entry.stableDedupDigest.copyBytes())
                statement.setObject(3, entry.campaignId)
                statement.setObject(4, entry.batchId)
                statement.setObject(5, entry.entryId)
                statement.setInt(6, entry.stableDedupKeyVersion)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """INSERT INTO voucher_pool_entries
                    (tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,stable_dedup_digest,
                     verification_digest,verification_key_version,code_ciphertext,code_nonce,wrapped_dek,
                     wrap_nonce,kek_version,revision)
                    VALUES (?,?,?,?,?,'AVAILABLE',?,NULL,NULL,?,?,?,?,?,0)""",
            ).use { statement ->
                statement.setString(1, entry.tenantId)
                statement.setObject(2, entry.entryId)
                statement.setObject(3, entry.campaignId)
                statement.setObject(4, entry.batchId)
                statement.setLong(5, entry.sourceOrdinal)
                statement.setBytes(6, entry.stableDedupDigest.copyBytes())
                statement.setBytes(7, entry.codeCiphertext.copyBytes())
                statement.setBytes(8, entry.codeNonce.copyBytes())
                statement.setBytes(9, entry.wrappedDek.copyBytes())
                statement.setBytes(10, entry.wrapNonce.copyBytes())
                statement.setString(11, entry.kekVersion)
                statement.executeUpdate()
            }
        }
    }

    override fun committedOrdinalDigests(
        connection: Connection,
        tenantId: String,
        batchId: UUID,
        firstOrdinal: Long,
        count: Int,
    ): List<CommittedOrdinalDigest> = connection.prepareStatement(
        """SELECT source_ordinal,stable_dedup_digest FROM voucher_pool_entries
            WHERE tenant_id=? AND batch_id=? AND source_ordinal>=? AND source_ordinal<?
            ORDER BY source_ordinal""",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setObject(2, batchId)
        statement.setLong(3, firstOrdinal)
        statement.setLong(4, firstOrdinal + count)
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    add(CommittedOrdinalDigest(result.getLong(1), DigestValue.of(result.getBytes(2))))
                }
            }
        }
    }

    override fun updateBatchCheckpoint(
        connection: Connection,
        batch: BatchRecord,
        nextSourceOrdinal: Long,
        acceptedCount: Long,
        rejectedCount: Long,
        checkpointDigest: DigestValue,
        state: BatchState,
        lastFailureCode: String?,
    ): BatchRecord {
        val updated = connection.prepareStatement(
            """UPDATE voucher_pool_batches
                SET next_source_ordinal=?,accepted_count=?,rejected_count=?,checkpoint_digest=?,state=?,
                    last_failure_code=?,revision=revision+1,updated_at=statement_timestamp()
                WHERE tenant_id=? AND batch_id=? AND revision=?""",
        ).use { statement ->
            statement.setLong(1, nextSourceOrdinal)
            statement.setLong(2, acceptedCount)
            statement.setLong(3, rejectedCount)
            statement.setBytes(4, checkpointDigest.copyBytes())
            statement.setString(5, state.name)
            statement.setString(6, lastFailureCode)
            statement.setString(7, batch.tenantId)
            statement.setObject(8, batch.batchId)
            statement.setLong(9, batch.revision)
            statement.executeUpdate()
        }
        check(updated == 1) { "batch checkpoint lost its revision" }
        return checkNotNull(lockBatchForUpdate(connection, batch.tenantId, batch.batchId))
    }

    override fun markBatchTerminalFailure(
        connection: Connection,
        batch: BatchRecord,
        rejectedCount: Long,
        failureCode: String,
    ): BatchRecord {
        val updated = connection.prepareStatement(
            """UPDATE voucher_pool_batches
                SET next_source_ordinal=?,rejected_count=?,state='FAILED_TERMINAL',last_failure_code=?,
                    revision=revision+1,updated_at=statement_timestamp()
                WHERE tenant_id=? AND batch_id=? AND revision=? AND state='STAGING'""",
        ).use { statement ->
            statement.setLong(1, batch.nextSourceOrdinal + rejectedCount)
            statement.setLong(2, batch.rejectedCount + rejectedCount)
            statement.setString(3, failureCode)
            statement.setString(4, batch.tenantId)
            statement.setObject(5, batch.batchId)
            statement.setLong(6, batch.revision)
            statement.executeUpdate()
        }
        check(updated == 1) { "batch terminal failure lost its revision" }
        return checkNotNull(lockBatchForUpdate(connection, batch.tenantId, batch.batchId))
    }

    override fun batchOrdinalCoverage(
        connection: Connection,
        tenantId: String,
        batchId: UUID,
    ): BatchOrdinalCoverage = connection.prepareStatement(
        """SELECT count(*),min(source_ordinal),max(source_ordinal) FROM voucher_pool_entries
            WHERE tenant_id=? AND batch_id=?""",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setObject(2, batchId)
        statement.executeQuery().use { result ->
            check(result.next())
            BatchOrdinalCoverage(
                result.getLong(1),
                result.getLong(2).takeUnless { result.wasNull() },
                result.getLong(3).takeUnless { result.wasNull() },
            )
        }
    }

    override fun activateBatch(connection: Connection, batch: BatchRecord): BatchRecord {
        val updated = connection.prepareStatement(
            """UPDATE voucher_pool_batches SET state='ACTIVE',revision=revision+1,updated_at=statement_timestamp()
                WHERE tenant_id=? AND batch_id=? AND revision=? AND state='STAGING'""",
        ).use { statement ->
            statement.setString(1, batch.tenantId)
            statement.setObject(2, batch.batchId)
            statement.setLong(3, batch.revision)
            statement.executeUpdate()
        }
        check(updated == 1) { "batch activation lost its revision" }
        return checkNotNull(lockBatchForUpdate(connection, batch.tenantId, batch.batchId))
    }

    override fun lockCampaignForShare(connection: Connection, tenantId: String, campaignId: UUID): CampaignRecord =
        connection.prepareStatement(
            "SELECT * FROM voucher_pool_campaigns WHERE tenant_id=? AND campaign_id=? FOR SHARE",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, campaignId)
            statement.executeQuery().use { result -> check(result.next()); result.campaignRecord() }
        }

    override fun lockBatchForShare(connection: Connection, tenantId: String, batchId: UUID): BatchRecord =
        connection.prepareStatement(
            "SELECT * FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=? FOR SHARE",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, batchId)
            statement.executeQuery().use { result -> check(result.next()); result.batchRecord() }
        }

    override fun lockUserLimit(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
        userDigest: ByteArray,
    ): UserLimitRecord {
        withExposed(connection) {
            VoucherPoolUserLimitTable.insertIgnore {
                it[VoucherPoolUserLimitTable.tenantId] = tenantId
                it[VoucherPoolUserLimitTable.campaignId] = campaignId
                it[VoucherPoolUserLimitTable.userDigest] = userDigest.copyOf()
                it[activeReservations] = 0
                it[activeAllocations] = 0
                it[lifetimeConsumed] = 0
                it[revision] = 0
            }
        }
        return connection.prepareStatement(
            "SELECT * FROM voucher_pool_user_limits WHERE tenant_id=? AND campaign_id=? AND user_digest=? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tenantId); statement.setObject(2, campaignId); statement.setBytes(3, userDigest)
            statement.executeQuery().use { result ->
                check(result.next())
                UserLimitRecord(
                    tenantId, campaignId, DigestValue.of(result.getBytes("user_digest")),
                    result.getInt("active_reservations"), result.getInt("active_allocations"),
                    result.getInt("lifetime_consumed"), result.getLong("revision"),
                )
            }
        }
    }

    override fun selectAvailableEntrySkipLocked(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
    ): EntryRecord? {
        lockEligibleCampaignForShare(connection, tenantId, campaignId) ?: return null
        return eligibleBatchIds(connection, tenantId, campaignId).firstNotNullOfOrNull { batchId ->
            lockEligibleBatchForShare(connection, tenantId, campaignId, batchId)
                ?.let { selectAvailableEntryInBatch(connection, tenantId, batchId) }
        }
    }

    @Suppress("ReturnCount") // Each stale revision must stop before a lower-order row is locked.
    override fun lockCanonicalChain(connection: Connection, candidate: WorkerCandidate): LockedWorkerChain? {
        val campaign = lockCampaignForUpdate(connection, candidate) ?: return null
        val batch = lockBatchForUpdate(connection, candidate) ?: return null
        val userLimits = lockExpectedUserLimits(connection, candidate) ?: return null
        val reservations = lockExpectedReservations(connection, candidate) ?: return null
        val entry = lockEntryForUpdate(connection, candidate) ?: return null
        return LockedWorkerChain(campaign, batch, entry, userLimits, reservations)
    }

    override fun appendAudit(connection: Connection, event: VoucherPoolAuditRecord) {
        withExposed(connection) {
            VoucherPoolAuditTable.insert {
                it[tenantId] = event.tenantId
                it[campaignId] = event.campaignId
                it[aggregateType] = event.aggregateType
                it[aggregateId] = event.aggregateId
                it[revision] = event.revision
                it[policyVersion] = event.policyVersion
                it[actorType] = event.actorType
                it[reasonCode] = event.reasonCode
                it[correlationDigest] = event.correlationDigest?.copyBytes()
                it[requestDigest] = event.requestDigest?.copyBytes()
            }
        }
    }

    override fun lockAllocatedCryptoEntry(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        entryId: UUID,
        sourceOrdinal: Long,
        expectedRevision: Long,
    ): LockedVoucherCryptoRecord? = connection.prepareStatement(
        """SELECT e.state,e.revision,e.stable_dedup_digest,d.key_version AS stable_dedup_key_version,
            e.code_ciphertext,e.code_nonce,e.wrapped_dek,e.wrap_nonce,e.kek_version
            FROM voucher_pool_entries e JOIN voucher_pool_code_dedup d
              ON d.tenant_id=e.tenant_id AND d.stable_dedup_digest=e.stable_dedup_digest
            WHERE e.tenant_id=? AND e.campaign_id=? AND e.batch_id=? AND e.entry_id=? AND e.source_ordinal=?
              AND e.revision=? AND e.state='ALLOCATED'
              AND e.revealed_at IS NULL AND e.quarantined_at IS NULL FOR UPDATE OF e""",
    ).use { statement ->
        var parameterIndex = 1
        statement.setString(parameterIndex++, tenantId)
        statement.setObject(parameterIndex++, campaignId)
        statement.setObject(parameterIndex++, batchId)
        statement.setObject(parameterIndex++, entryId)
        statement.setLong(parameterIndex++, sourceOrdinal)
        statement.setLong(parameterIndex, expectedRevision)
        statement.executeQuery().use { result -> if (result.next()) result.lockedVoucherCryptoRecord() else null }
    }

    override fun eraseVoucherCiphertext(
        connection: Connection,
        tenantId: String,
        entryId: UUID,
        expectedRevision: Long,
    ) {
        val updated = withExposed(connection) {
            VoucherPoolEntryTable.update({
                (VoucherPoolEntryTable.tenantId eq tenantId) and
                    (VoucherPoolEntryTable.entryId eq entryId) and
                    (VoucherPoolEntryTable.revision eq expectedRevision) and
                    VoucherPoolEntryTable.quarantinedAt.isNull()
            }) {
                it[revealedAt] = CurrentTimestamp
                it[codeCiphertext] = null
                it[codeNonce] = null
                it[wrappedDek] = null
                it[wrapNonce] = null
                it[kekVersion] = null
                it[revision] = expectedRevision + 1
            }
        }
        check(updated == 1) { "voucher ciphertext erase lost its revision" }
    }

    override fun quarantineVoucherCrypto(
        connection: Connection,
        tenantId: String,
        entryId: UUID,
        sourceState: EntryState,
        sourceRevision: Long,
        reasonCode: String,
    ) {
        withExposed(connection) {
            val entryUpdated = VoucherPoolEntryTable.update({
                (VoucherPoolEntryTable.tenantId eq tenantId) and
                    (VoucherPoolEntryTable.entryId eq entryId) and
                    (VoucherPoolEntryTable.revision eq sourceRevision) and
                    VoucherPoolEntryTable.quarantinedAt.isNull()
            }) {
                it[quarantinedAt] = CurrentTimestamp
                it[revision] = sourceRevision + 1
            }
            check(entryUpdated == 1) { "voucher quarantine lost its revision" }

            val reactivated = VoucherPoolQuarantineTable.update({
                (VoucherPoolQuarantineTable.tenantId eq tenantId) and
                    (VoucherPoolQuarantineTable.entryId eq entryId) and
                    VoucherPoolQuarantineTable.resolvedAt.isNotNull()
            }) {
                it[VoucherPoolQuarantineTable.sourceState] = sourceState.name
                it[VoucherPoolQuarantineTable.sourceRevision] = sourceRevision
                it[VoucherPoolQuarantineTable.reasonCode] = reasonCode
                it[detectedAt] = CurrentTimestamp
                it[resolvedAt] = null
                it[resolution] = null
            }
            if (reactivated == 0) {
                VoucherPoolQuarantineTable.insert {
                    it[VoucherPoolQuarantineTable.tenantId] = tenantId
                    it[VoucherPoolQuarantineTable.entryId] = entryId
                    it[VoucherPoolQuarantineTable.sourceState] = sourceState.name
                    it[VoucherPoolQuarantineTable.sourceRevision] = sourceRevision
                    it[VoucherPoolQuarantineTable.reasonCode] = reasonCode
                }
            }
        }
    }

    internal fun lockEntry(connection: Connection, tenantId: String, entryId: UUID): EntryRecord =
        connection.prepareStatement(
            "SELECT * FROM voucher_pool_entries WHERE tenant_id=? AND entry_id=? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tenantId); statement.setObject(2, entryId)
            statement.executeQuery().use { result -> check(result.next()); result.entryRecord() }
        }

    internal fun selectWorkerCandidates(
        connection: Connection,
        tenantId: String,
        batchId: UUID,
        limit: Int,
    ): List<WorkerCandidate> = withExposed(connection) {
        val batch = VoucherPoolBatchTable.select(
            VoucherPoolBatchTable.campaignId,
            VoucherPoolBatchTable.revision,
        ).where {
            (VoucherPoolBatchTable.tenantId eq tenantId) and (VoucherPoolBatchTable.batchId eq batchId)
        }.singleOrNull() ?: return@withExposed emptyList()
        val campaignId = batch[VoucherPoolBatchTable.campaignId]
        val campaignRevision = VoucherPoolCampaignTable.select(VoucherPoolCampaignTable.revision).where {
            (VoucherPoolCampaignTable.tenantId eq tenantId) and
                (VoucherPoolCampaignTable.campaignId eq campaignId)
        }.single()[VoucherPoolCampaignTable.revision]
        workerEntryQuery(tenantId, batchId, limit).map { row ->
            WorkerCandidate(
                tenantId = row[VoucherPoolEntryTable.tenantId],
                campaignId = row[VoucherPoolEntryTable.campaignId],
                batchId = row[VoucherPoolEntryTable.batchId],
                entryId = row[VoucherPoolEntryTable.entryId],
                expectedCampaignRevision = campaignRevision,
                expectedBatchRevision = batch[VoucherPoolBatchTable.revision],
                expectedEntryRevision = row[VoucherPoolEntryTable.revision],
            )
        }
    }

    internal fun poolDepth(tenantId: String, batchId: UUID): Map<EntryState, Long> = transaction(database) {
        val (query, depth) = poolDepthQuery(tenantId, batchId)
        query
            .associate { it[VoucherPoolDepthTable.state] to checkNotNull(it[depth]) }
    }

    internal fun workerCandidatePlanSql(connection: Connection, tenantId: String, batchId: UUID, limit: Int): String =
        withExposed(connection) { workerEntryQuery(tenantId, batchId, limit).prepareSQL(TransactionManager.current(), false) }

    internal fun poolDepthPlanSql(connection: Connection, tenantId: String, batchId: UUID): String =
        withExposed(connection) { poolDepthQuery(tenantId, batchId).first.prepareSQL(TransactionManager.current(), false) }

    private fun workerEntryQuery(tenantId: String, batchId: UUID, limit: Int) =
        VoucherPoolEntryTable.select(
            VoucherPoolEntryTable.tenantId,
            VoucherPoolEntryTable.campaignId,
            VoucherPoolEntryTable.batchId,
            VoucherPoolEntryTable.entryId,
            VoucherPoolEntryTable.revision,
        ).where {
            (VoucherPoolEntryTable.tenantId eq tenantId) and
                (VoucherPoolEntryTable.batchId eq batchId) and
                (VoucherPoolEntryTable.state eq EntryState.AVAILABLE) and
                VoucherPoolEntryTable.quarantinedAt.isNull()
        }.orderBy(
            VoucherPoolEntryTable.sourceOrdinal to SortOrder.ASC,
            VoucherPoolEntryTable.entryId to SortOrder.ASC,
        ).limit(limit)

    private fun poolDepthQuery(tenantId: String, batchId: UUID) = VoucherPoolDepthTable.entryCount.sum().let { depth ->
        VoucherPoolDepthTable.select(VoucherPoolDepthTable.state, depth)
            .where { (VoucherPoolDepthTable.tenantId eq tenantId) and (VoucherPoolDepthTable.batchId eq batchId) }
            .groupBy(VoucherPoolDepthTable.state)
            .orderBy(VoucherPoolDepthTable.state, SortOrder.ASC) to depth
    }

    @Suppress("SpreadOperator")
    private fun <T> withExposed(connection: Connection, block: () -> T): T {
        val proxy = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "close", "commit", "rollback", "abort", "setAutoCommit", "setReadOnly", "setTransactionIsolation",
                "setCatalog", "setSchema", "setNetworkTimeout",
                -> null
                else -> try {
                    method.invoke(connection, *(arguments ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as Connection
        val exposedDatabase = Database.connect(getNewConnection = { proxy })
        return try {
            transaction(exposedDatabase) { block() }
        } finally {
            TransactionManager.closeAndUnregister(exposedDatabase)
        }
    }

    private fun selectAvailableEntryInBatch(connection: Connection, tenantId: String, batchId: UUID): EntryRecord? =
        connection.prepareStatement(ALLOCATION_CANDIDATE_SQL).use { statement ->
            statement.setString(1, tenantId); statement.setObject(2, batchId)
            statement.executeQuery().use { result -> if (result.next()) result.entryRecord() else null }
        }

    private fun lockExpectedUserLimits(
        connection: Connection,
        candidate: WorkerCandidate,
    ): List<UserLimitRecord>? {
        val locked = mutableListOf<UserLimitRecord>()
        for (expected in candidate.userLimits.sortedBy { it.userDigest.copyBytes().toHexString() }) {
            locked += lockExistingUserLimit(connection, candidate, expected) ?: return null
        }
        return locked
    }

    private fun lockExpectedReservations(
        connection: Connection,
        candidate: WorkerCandidate,
    ): List<ReservationRecord>? {
        val locked = mutableListOf<ReservationRecord>()
        for (expected in candidate.reservations.sortedBy { it.reservationId.toString() }) {
            locked += lockReservation(connection, candidate, expected) ?: return null
        }
        return locked
    }

    private fun lockCampaignForUpdate(connection: Connection, candidate: WorkerCandidate): CampaignRecord? =
        connection.prepareStatement(
            """SELECT * FROM voucher_pool_campaigns
                WHERE tenant_id=? AND campaign_id=? AND revision=? FOR UPDATE""",
        ).use { statement ->
            statement.setString(1, candidate.tenantId); statement.setObject(2, candidate.campaignId)
            statement.setLong(3, candidate.expectedCampaignRevision)
            statement.executeQuery().use { result -> if (result.next()) result.campaignRecord() else null }
        }

    private fun lockEligibleCampaignForShare(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
    ): CampaignRecord? = connection.prepareStatement(
        """SELECT * FROM voucher_pool_campaigns WHERE tenant_id=? AND campaign_id=? AND state='ACTIVE'
            AND starts_at<=transaction_timestamp() AND ends_at>transaction_timestamp() FOR SHARE""",
    ).use { statement ->
        statement.setString(1, tenantId); statement.setObject(2, campaignId)
        statement.executeQuery().use { result -> if (result.next()) result.campaignRecord() else null }
    }

    private fun eligibleBatchIds(connection: Connection, tenantId: String, campaignId: UUID): List<UUID> =
        withExposed(connection) {
            VoucherPoolBatchTable.select(VoucherPoolBatchTable.batchId)
                .where {
                    (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.campaignId eq campaignId) and
                        (VoucherPoolBatchTable.state eq BatchState.ACTIVE) and
                        (VoucherPoolBatchTable.activatesAt lessEq CurrentTimestamp) and
                        (VoucherPoolBatchTable.expiresAt.isNull() or
                            (VoucherPoolBatchTable.expiresAt greater CurrentTimestamp))
                }.orderBy(
                    VoucherPoolBatchTable.activatesAt to SortOrder.ASC,
                    VoucherPoolBatchTable.batchId to SortOrder.ASC,
                ).map { it[VoucherPoolBatchTable.batchId] }
        }

    private fun lockEligibleBatchForShare(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
    ): BatchRecord? = connection.prepareStatement(
        """SELECT * FROM voucher_pool_batches WHERE tenant_id=? AND campaign_id=? AND batch_id=? AND state='ACTIVE'
            AND activates_at<=transaction_timestamp()
            AND (expires_at IS NULL OR expires_at>transaction_timestamp()) FOR SHARE""",
    ).use { statement ->
        statement.setString(1, tenantId); statement.setObject(2, campaignId); statement.setObject(3, batchId)
        statement.executeQuery().use { result -> if (result.next()) result.batchRecord() else null }
    }

    private fun lockBatchForUpdate(connection: Connection, candidate: WorkerCandidate): BatchRecord? =
        connection.prepareStatement(
            """SELECT * FROM voucher_pool_batches
                WHERE tenant_id=? AND batch_id=? AND campaign_id=? AND revision=? FOR UPDATE""",
        ).use { statement ->
            statement.setString(1, candidate.tenantId); statement.setObject(2, candidate.batchId)
            statement.setObject(3, candidate.campaignId); statement.setLong(4, candidate.expectedBatchRevision)
            statement.executeQuery().use { result -> if (result.next()) result.batchRecord() else null }
        }

    private fun lockExistingUserLimit(
        connection: Connection,
        candidate: WorkerCandidate,
        expected: ExpectedUserLimit,
    ): UserLimitRecord? = connection.prepareStatement(
        """SELECT * FROM voucher_pool_user_limits WHERE tenant_id=? AND campaign_id=?
            AND user_digest=? AND revision=? FOR UPDATE""",
    ).use { statement ->
        statement.setString(1, candidate.tenantId); statement.setObject(2, candidate.campaignId)
        statement.setBytes(3, expected.userDigest.copyBytes()); statement.setLong(4, expected.expectedRevision)
        statement.executeQuery().use { result ->
            if (!result.next()) null else UserLimitRecord(
                candidate.tenantId, candidate.campaignId, DigestValue.of(result.getBytes("user_digest")),
                result.getInt("active_reservations"), result.getInt("active_allocations"),
                result.getInt("lifetime_consumed"), result.getLong("revision"),
            )
        }
    }

    private fun lockReservation(
        connection: Connection,
        candidate: WorkerCandidate,
        expected: ExpectedReservation,
    ): ReservationRecord? = connection.prepareStatement(
        """SELECT * FROM voucher_pool_reservations
            WHERE tenant_id=? AND reservation_id=? AND campaign_id=? AND batch_id=? AND entry_id=?
              AND revision=? FOR UPDATE""",
    ).use { statement ->
        statement.setString(1, candidate.tenantId); statement.setObject(2, expected.reservationId)
        statement.setObject(3, candidate.campaignId); statement.setObject(4, candidate.batchId)
        statement.setObject(5, candidate.entryId); statement.setLong(6, expected.expectedRevision)
        statement.executeQuery().use { result -> if (result.next()) result.reservationRecord() else null }
    }

    private fun lockEntryForUpdate(connection: Connection, candidate: WorkerCandidate): EntryRecord? =
        connection.prepareStatement(
            """SELECT * FROM voucher_pool_entries WHERE tenant_id=? AND entry_id=? AND campaign_id=?
                AND batch_id=? AND revision=? FOR UPDATE""",
        ).use { statement ->
            statement.setString(1, candidate.tenantId); statement.setObject(2, candidate.entryId)
            statement.setObject(3, candidate.campaignId); statement.setObject(4, candidate.batchId)
            statement.setLong(5, candidate.expectedEntryRevision)
            statement.executeQuery().use { result -> if (result.next()) result.entryRecord() else null }
        }

    private fun ResultSet.campaignRecord() = CampaignRecord(
        tenantId = getString("tenant_id"), campaignId = getObject("campaign_id", UUID::class.java),
        state = CampaignState.valueOf(getString("state")), policyVersion = getLong("policy_version"),
        startsAt = getTimestamp("starts_at").toInstant(), endsAt = getTimestamp("ends_at").toInstant(),
        perUserLimit = getInt("per_user_limit"), reservationTtlSeconds = getLong("reservation_ttl_seconds"),
        allocationTtlSeconds = getLong("allocation_ttl_seconds"), replacementAllowance = getInt("replacement_allowance"),
        revision = getLong("revision"), createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
    )

    private fun ResultSet.batchRecord() = BatchRecord(
        tenantId = getString("tenant_id"), batchId = getObject("batch_id", UUID::class.java),
        campaignId = getObject("campaign_id", UUID::class.java), state = BatchState.valueOf(getString("state")),
        sourceKind = getString("source_kind"), provenanceDigest = DigestValue.of(getBytes("provenance_digest")),
        requestFingerprint = DigestValue.of(getBytes("request_fingerprint")), policyVersion = getLong("policy_version"),
        activatesAt = getTimestamp("activates_at").toInstant(), expiresAt = getTimestamp("expires_at")?.toInstant(),
        nextSourceOrdinal = getLong("next_source_ordinal"), expectedCount = getLong("expected_count"),
        acceptedCount = getLong("accepted_count"), rejectedCount = getLong("rejected_count"),
        checkpointDigest = getBytes("checkpoint_digest")?.let(DigestValue::of),
        lastFailureCode = getString("last_failure_code"), revision = getLong("revision"),
        createdAt = getTimestamp("created_at").toInstant(), updatedAt = getTimestamp("updated_at").toInstant(),
    )

    private fun ResultSet.entryRecord() = EntryRecord(
        tenantId = getString("tenant_id"), entryId = getObject("entry_id", UUID::class.java),
        campaignId = getObject("campaign_id", UUID::class.java), batchId = getObject("batch_id", UUID::class.java),
        sourceOrdinal = getLong("source_ordinal"), state = EntryState.valueOf(getString("state")),
        stableDedupDigest = DigestValue.of(getBytes("stable_dedup_digest")),
        verificationDigest = getBytes("verification_digest")?.let(DigestValue::of),
        verificationKeyVersion = getInt("verification_key_version").takeUnless { wasNull() },
        codeCiphertext = getBytes("code_ciphertext")?.let(DigestValue::of),
        codeNonce = getBytes("code_nonce")?.let(DigestValue::of), wrappedDek = getBytes("wrapped_dek")?.let(DigestValue::of),
        wrapNonce = getBytes("wrap_nonce")?.let(DigestValue::of), kekVersion = getString("kek_version"),
        reservationId = getObject("reservation_id", UUID::class.java), allocationId = getObject("allocation_id", UUID::class.java),
        userDigest = getBytes("user_digest")?.let(DigestValue::of), reservedAt = getTimestamp("reserved_at")?.toInstant(),
        reservationExpiresAt = getTimestamp("reservation_expires_at")?.toInstant(),
        allocatedAt = getTimestamp("allocated_at")?.toInstant(),
        allocationExpiresAt = getTimestamp("allocation_expires_at")?.toInstant(),
        revealedAt = getTimestamp("revealed_at")?.toInstant(), redeemedAt = getTimestamp("redeemed_at")?.toInstant(),
        allocationPolicyVersion = getLong("allocation_policy_version").takeUnless { wasNull() },
        terminalReason = getString("terminal_reason"), entitlementRootId = getObject("entitlement_root_id", UUID::class.java),
        replacementCount = getInt("replacement_count"), quarantinedAt = getTimestamp("quarantined_at")?.toInstant(),
        revision = getLong("revision"), createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
    )

    private fun ResultSet.lockedVoucherCryptoRecord() = LockedVoucherCryptoRecord(
        state = EntryState.valueOf(getString("state")),
        revision = getLong("revision"),
        stableDedupDigest = getBytes("stable_dedup_digest")?.let(DigestValue::of),
        stableDedupKeyVersion = getInt("stable_dedup_key_version"),
        codeCiphertext = getBytes("code_ciphertext")?.let(DigestValue::of),
        codeNonce = getBytes("code_nonce")?.let(DigestValue::of),
        wrappedDek = getBytes("wrapped_dek")?.let(DigestValue::of),
        wrapNonce = getBytes("wrap_nonce")?.let(DigestValue::of),
        kekVersion = getString("kek_version"),
    )

    private fun ResultSet.reservationRecord() = ReservationRecord(
        tenantId = getString("tenant_id"), reservationId = getObject("reservation_id", UUID::class.java),
        campaignId = getObject("campaign_id", UUID::class.java), batchId = getObject("batch_id", UUID::class.java),
        entryId = getObject("entry_id", UUID::class.java), userDigest = DigestValue.of(getBytes("user_digest")),
        idempotencyOwnerDigest = DigestValue.of(getBytes("idempotency_owner_digest")), state = getString("state"),
        expiresAt = getTimestamp("reservation_expires_at").toInstant(), policyVersion = getLong("policy_version"),
        revision = getLong("revision"),
    )

    internal companion object {
        const val ALLOCATION_CANDIDATE_SQL =
            """SELECT * FROM voucher_pool_entries
                WHERE tenant_id=? AND batch_id=? AND state='AVAILABLE' AND quarantined_at IS NULL
                ORDER BY source_ordinal,entry_id LIMIT 1 FOR UPDATE SKIP LOCKED"""
    }
}
