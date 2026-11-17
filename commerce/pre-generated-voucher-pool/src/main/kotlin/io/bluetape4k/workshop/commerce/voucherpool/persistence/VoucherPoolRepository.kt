@file:Suppress(
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "ReturnCount", // Stale hint validation exits before any lower-order row lock is acquired.
    "TooManyFunctions",
    "UnusedParameter", "SqlResolve",
)

package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.*

internal interface VoucherPoolRepository {

    fun transactionTime(): Instant
    fun createCampaign(campaign: CampaignRecord): CampaignRecord
    fun lockCampaignForUpdate(tenantId: String, campaignId: UUID): CampaignRecord?
    fun userIdentityKeyVersion(tenantId: String, campaignId: UUID): Int?
    fun updateCampaign(campaign: CampaignRecord, expectedRevision: Long): CampaignRecord
    fun createBatch(batch: BatchRecord): BatchRecord
    fun lockBatchForUpdate(tenantId: String, batchId: UUID): BatchRecord?
    fun insertPreparedEntries(entries: List<PreparedVoucherEntryRecord>)
    fun committedOrdinalDigests(
        tenantId: String,
        batchId: UUID,
        firstOrdinal: Long,
        count: Int,
    ): List<CommittedOrdinalDigest>

    fun updateBatchCheckpoint(
        batch: BatchRecord,
        nextSourceOrdinal: Long,
        acceptedCount: Long,
        rejectedCount: Long,
        checkpointDigest: DigestValue,
        state: BatchState,
        lastFailureCode: String?,
    ): BatchRecord

    fun markBatchTerminalFailure(
        batch: BatchRecord,
        rejectedCount: Long,
        failureCode: String,
    ): BatchRecord

    fun batchOrdinalCoverage(tenantId: String, batchId: UUID): BatchOrdinalCoverage
    fun activateBatch(batch: BatchRecord): BatchRecord
    fun updateBatchState(batch: BatchRecord, state: BatchState): BatchRecord
    fun lockCampaignForShare(tenantId: String, campaignId: UUID): CampaignRecord
    fun lockBatchForShare(tenantId: String, batchId: UUID): BatchRecord
    fun lockUserLimit(tenantId: String, campaignId: UUID, userDigest: ByteArray): UserLimitRecord
    fun selectAvailableEntrySkipLocked(
        tenantId: String,
        campaignId: UUID,
        lockedBatchIds: List<UUID>,
    ): EntryRecord?

    fun lockReservationGuards(
        tenantId: String,
        campaignId: UUID,
        userDigest: ByteArray,
    ): ReservationGuards?

    fun hasAvailableEligibleEntry(
        tenantId: String,
        campaignId: UUID,
        lockedBatchIds: List<UUID>,
    ): Boolean

    fun createReservation(
        reservation: ReservationRecord,
        entry: EntryRecord,
        userLimit: UserLimitRecord,
    ): ReservationRecord

    fun lockReservationChain(
        tenantId: String,
        reservationId: UUID,
        userDigest: ByteArray,
    ): LockedReservationChain?

    fun releaseReservation(chain: LockedReservationChain): ReservationRecord
    fun allocateReservation(
        chain: LockedReservationChain,
        allocation: AllocationRecord,
        verificationDigest: ByteArray,
        verificationKeyVersion: Int,
    ): LockedAllocationChain

    fun lockAllocationChain(
        tenantId: String,
        allocationId: UUID,
        userDigest: ByteArray?,
    ): LockedAllocationChain?

    fun lockReplacementChain(
        tenantId: String,
        allocationId: UUID,
        userDigest: ByteArray,
    ): LockedReplacementChain?

    fun replaceLostReveal(
        chain: LockedReplacementChain,
        reservation: ReservationRecord,
    ): ReservationRecord

    fun transitionAllocationTerminal(
        chain: LockedAllocationChain,
        state: EntryState,
        reason: String,
    ): LockedAllocationChain

    fun lockReservedCryptoEntry(
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        entryId: UUID,
        sourceOrdinal: Long,
        expectedRevision: Long,
    ): LockedVoucherCryptoRecord?

    fun lockCanonicalChain(candidate: WorkerCandidate): LockedWorkerChain?
    fun expireReservation(chain: LockedWorkerChain): Boolean
    fun terminalizeWorkerEntry(
        chain: LockedWorkerChain,
        targetState: EntryState,
        reasonCode: String,
    ): Boolean

    fun lockAllocatedCryptoEntry(
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        entryId: UUID,
        sourceOrdinal: Long,
        expectedRevision: Long,
    ): LockedVoucherCryptoRecord?

    fun eraseVoucherCiphertext(tenantId: String, entryId: UUID, expectedRevision: Long)
    fun advanceAllocationRevision(allocation: AllocationRecord): AllocationRecord
    fun quarantineVoucherCrypto(
        tenantId: String,
        entryId: UUID,
        sourceState: EntryState,
        sourceRevision: Long,
        reasonCode: String,
    )

    fun appendAudit(event: VoucherPoolAuditRecord)
}

/**
 * transaction-bound PostgreSQL authority repository입니다.
 *
 * bluetape4k [io.bluetape4k.exposed.jdbc.repository.JdbcRepository]처럼 caller가 active Exposed transaction을 제공합니다.
 * campaign/batch/user-limit의 일반 CRUD·CAS와 tenant-scoped 조회는 Exposed table/DSL을 사용합니다.
 * raw JDBC 잔여 경계는 다음 PostgreSQL 전용 경로뿐입니다.
 *
 * - [transactionTime]과 crypto/query의 observed time: `transaction_timestamp()` snapshot
 * - campaign/batch share lock: `FOR SHARE`와 canonical lock ordering
 * - available candidate: `ANY(uuid[])`와 `FOR UPDATE OF ... SKIP LOCKED`
 * - reservation/allocation terminal transition과 prepared-entry bulk write: server timestamp와
 *   entry·reservation·user-limit·depth의 고정 갱신 순서
 * - crypto lock과 pool-depth projection: `FOR UPDATE OF`, `ON CONFLICT` arithmetic, 음수 방지 검증
 */
@Suppress("LargeClass")
internal class JdbcVoucherPoolRepository: VoucherPoolRepository {

    internal companion object: KLogging() {
        val ALLOCATION_CANDIDATE_SQL =
            """
            SELECT * FROM voucher_pool_entries
             WHERE tenant_id=? AND batch_id=? 
               AND state='AVAILABLE' 
               AND quarantined_at IS NULL
             ORDER BY source_ordinal, entry_id 
             LIMIT 1 
               FOR UPDATE SKIP LOCKED
             """.trimIndent()
    }

    private val connection: Connection
        get() = TransactionManager.currentOrNull()
            ?.connection
            ?.connection as? Connection
            ?: error("voucher pool repository requires an active JDBC transaction")


    /** PostgreSQL transaction snapshot을 authority 시간으로 사용하므로 DSL로 대체하지 않습니다. */
    override fun transactionTime(): Instant =
        connection.prepareStatement("SELECT transaction_timestamp()").use { statement ->
            statement.executeQuery().use { result ->
                result.next()
                result.getTimestamp(1).toInstant()
            }
        }

    override fun createCampaign(campaign: CampaignRecord): CampaignRecord {
        VoucherPoolCampaignTable.insert {
            it[VoucherPoolCampaignTable.tenantId] = campaign.tenantId
            it[VoucherPoolCampaignTable.campaignId] = campaign.campaignId
            it[VoucherPoolCampaignTable.state] = campaign.state
            it[VoucherPoolCampaignTable.startsAt] = campaign.startsAt
            it[VoucherPoolCampaignTable.endsAt] = campaign.endsAt
            it[VoucherPoolCampaignTable.perUserLimit] = campaign.perUserLimit
            it[VoucherPoolCampaignTable.reservationTtlSeconds] = campaign.reservationTtlSeconds
            it[VoucherPoolCampaignTable.allocationTtlSeconds] = campaign.allocationTtlSeconds
            it[VoucherPoolCampaignTable.replacementAllowance] = campaign.replacementAllowance
            it[VoucherPoolCampaignTable.userIdentityKeyVersion] = campaign.userIdentityKeyVersion
            it[VoucherPoolCampaignTable.policyVersion] = campaign.policyVersion
            it[VoucherPoolCampaignTable.revision] = campaign.revision
        }
        return lockCampaignForUpdate(campaign.tenantId, campaign.campaignId).checkNotNull { "lock campaign for update" }
    }

    override fun lockCampaignForUpdate(
        tenantId: String,
        campaignId: UUID,
    ): CampaignRecord? = VoucherPoolCampaignTable
        .selectAll()
        .where {
            (VoucherPoolCampaignTable.tenantId eq tenantId) and
                    (VoucherPoolCampaignTable.campaignId eq campaignId)
        }
        .forUpdate()
        .singleOrNull()
        ?.campaignRecord()

    override fun userIdentityKeyVersion(tenantId: String, campaignId: UUID): Int? =
        VoucherPoolCampaignTable
            .select(VoucherPoolCampaignTable.userIdentityKeyVersion)
            .where {
                (VoucherPoolCampaignTable.tenantId eq tenantId) and
                        (VoucherPoolCampaignTable.campaignId eq campaignId)
            }
            .singleOrNull()
            ?.get(VoucherPoolCampaignTable.userIdentityKeyVersion)

    override fun updateCampaign(
        campaign: CampaignRecord,
        expectedRevision: Long,
    ): CampaignRecord {
        val updated = VoucherPoolCampaignTable.update({
            (VoucherPoolCampaignTable.tenantId eq campaign.tenantId) and
                    (VoucherPoolCampaignTable.campaignId eq campaign.campaignId) and
                    (VoucherPoolCampaignTable.revision eq expectedRevision)
        }) {
            it[VoucherPoolCampaignTable.state] = campaign.state
            it[VoucherPoolCampaignTable.startsAt] = campaign.startsAt
            it[VoucherPoolCampaignTable.endsAt] = campaign.endsAt
            it[VoucherPoolCampaignTable.perUserLimit] = campaign.perUserLimit
            it[VoucherPoolCampaignTable.reservationTtlSeconds] = campaign.reservationTtlSeconds
            it[VoucherPoolCampaignTable.allocationTtlSeconds] = campaign.allocationTtlSeconds
            it[VoucherPoolCampaignTable.replacementAllowance] = campaign.replacementAllowance
            it[VoucherPoolCampaignTable.policyVersion] = campaign.policyVersion
            it[VoucherPoolCampaignTable.revision] = VoucherPoolCampaignTable.revision + 1
            it[VoucherPoolCampaignTable.updatedAt] = CurrentTimestamp
        }
        check(updated == 1) { "campaign update lost its revision" }
        return lockCampaignForUpdate(campaign.tenantId, campaign.campaignId).checkNotNull { "lock campaign for update" }
    }

    override fun createBatch(batch: BatchRecord): BatchRecord {
        VoucherPoolBatchTable.insert {
            it[VoucherPoolBatchTable.tenantId] = batch.tenantId
            it[VoucherPoolBatchTable.batchId] = batch.batchId
            it[VoucherPoolBatchTable.campaignId] = batch.campaignId
            it[VoucherPoolBatchTable.state] = batch.state
            it[VoucherPoolBatchTable.sourceKind] = batch.sourceKind
            it[VoucherPoolBatchTable.provenanceDigest] = batch.provenanceDigest.copyBytes()
            it[VoucherPoolBatchTable.requestFingerprint] = batch.requestFingerprint.copyBytes()
            it[VoucherPoolBatchTable.policyVersion] = batch.policyVersion
            it[VoucherPoolBatchTable.activatesAt] = batch.activatesAt
            it[VoucherPoolBatchTable.expiresAt] = batch.expiresAt
            it[VoucherPoolBatchTable.nextSourceOrdinal] = batch.nextSourceOrdinal
            it[VoucherPoolBatchTable.expectedCount] = batch.expectedCount
            it[VoucherPoolBatchTable.acceptedCount] = batch.acceptedCount
            it[VoucherPoolBatchTable.rejectedCount] = batch.rejectedCount
            it[VoucherPoolBatchTable.checkpointDigest] = batch.checkpointDigest?.copyBytes()
            it[VoucherPoolBatchTable.lastFailureCode] = batch.lastFailureCode
            it[VoucherPoolBatchTable.revision] = batch.revision
        }
        return lockBatchForUpdate(batch.tenantId, batch.batchId)
            .checkNotNull { "lock batch does not exist. tenantId=${batch.tenantId}, batchId=${batch.batchId}" }
    }

    override fun lockBatchForUpdate(tenantId: String, batchId: UUID): BatchRecord? =
        VoucherPoolBatchTable
            .selectAll()
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.batchId eq batchId)
            }
            .forUpdate()
            .singleOrNull()
            ?.batchRecord()

    override fun insertPreparedEntries(entries: List<PreparedVoucherEntryRecord>) {
        entries.forEach { entry ->
            connection.prepareStatement(
                """INSERT INTO voucher_pool_code_dedup
                    (tenant_id,stable_dedup_digest,first_campaign_id,first_batch_id,first_entry_id,key_version)
                    VALUES (?,?,?,?,?,?)""".trimIndent(),
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
                    VALUES (?,?,?,?,?,'AVAILABLE',?,NULL,NULL,?,?,?,?,?,0)""".trimIndent(),
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
        entries
            .groupingBy { it.tenantId to it.batchId }
            .eachCount()
            .forEach { (scope, count) ->
                ensurePoolDepthRows(
                    connection = connection,
                    tenantId = scope.first,
                    batchId = scope.second
                )
                incrementPoolDepth(
                    connection = connection,
                    tenantId = scope.first,
                    batchId = scope.second,
                    state = EntryState.AVAILABLE,
                    count = count.toLong()
                )
            }
    }

    override fun committedOrdinalDigests(
        tenantId: String,
        batchId: UUID,
        firstOrdinal: Long,
        count: Int,
    ): List<CommittedOrdinalDigest> = VoucherPoolEntryTable
        .select(
            VoucherPoolEntryTable.sourceOrdinal,
            VoucherPoolEntryTable.stableDedupDigest,
        )
        .where {
            (VoucherPoolEntryTable.tenantId eq tenantId) and
                    (VoucherPoolEntryTable.batchId eq batchId) and
                    (VoucherPoolEntryTable.sourceOrdinal greaterEq firstOrdinal) and
                    (VoucherPoolEntryTable.sourceOrdinal less firstOrdinal + count)
        }
        .orderBy(VoucherPoolEntryTable.sourceOrdinal to SortOrder.ASC)
        .map {
            CommittedOrdinalDigest(
                sourceOrdinal = it[VoucherPoolEntryTable.sourceOrdinal],
                stableDedupDigest = DigestValue.of(it[VoucherPoolEntryTable.stableDedupDigest]),
            )
        }

    override fun updateBatchCheckpoint(
        batch: BatchRecord,
        nextSourceOrdinal: Long,
        acceptedCount: Long,
        rejectedCount: Long,
        checkpointDigest: DigestValue,
        state: BatchState,
        lastFailureCode: String?,
    ): BatchRecord {
        val updated = VoucherPoolBatchTable.update({
            (VoucherPoolBatchTable.tenantId eq batch.tenantId) and
                    (VoucherPoolBatchTable.batchId eq batch.batchId) and
                    (VoucherPoolBatchTable.revision eq batch.revision)
        }) {
            it[VoucherPoolBatchTable.nextSourceOrdinal] = nextSourceOrdinal
            it[VoucherPoolBatchTable.acceptedCount] = acceptedCount
            it[VoucherPoolBatchTable.rejectedCount] = rejectedCount
            it[VoucherPoolBatchTable.checkpointDigest] = checkpointDigest.copyBytes()
            it[VoucherPoolBatchTable.state] = state
            it[VoucherPoolBatchTable.lastFailureCode] = lastFailureCode
            it[VoucherPoolBatchTable.revision] = VoucherPoolBatchTable.revision + 1
            it[VoucherPoolBatchTable.updatedAt] = CurrentTimestamp
        }
        check(updated == 1) { "batch checkpoint lost its revision" }
        return lockBatchForUpdate(batch.tenantId, batch.batchId)
            .checkNotNull { "lock batch does not exist. tenantId=${batch.tenantId}, batchId=${batch.batchId}" }
    }

    override fun markBatchTerminalFailure(
        batch: BatchRecord,
        rejectedCount: Long,
        failureCode: String,
    ): BatchRecord {
        val updated = VoucherPoolBatchTable.update({
            (VoucherPoolBatchTable.tenantId eq batch.tenantId) and
                    (VoucherPoolBatchTable.batchId eq batch.batchId) and
                    (VoucherPoolBatchTable.revision eq batch.revision) and
                    (VoucherPoolBatchTable.state eq BatchState.STAGING)
        }) {
            it[VoucherPoolBatchTable.nextSourceOrdinal] = batch.nextSourceOrdinal + rejectedCount
            it[VoucherPoolBatchTable.rejectedCount] = batch.rejectedCount + rejectedCount
            it[VoucherPoolBatchTable.state] = BatchState.FAILED_TERMINAL
            it[VoucherPoolBatchTable.lastFailureCode] = failureCode
            it[VoucherPoolBatchTable.revision] = VoucherPoolBatchTable.revision + 1
            it[VoucherPoolBatchTable.updatedAt] = CurrentTimestamp
        }
        check(updated == 1) { "batch terminal failure lost its revision" }
        return lockBatchForUpdate(batch.tenantId, batch.batchId)
            .checkNotNull { "lock batch does not exist. tenantId=${batch.tenantId}, batchId=${batch.batchId}" }
    }

    override fun batchOrdinalCoverage(
        tenantId: String,
        batchId: UUID,
    ): BatchOrdinalCoverage {
        val entryCount = VoucherPoolEntryTable.entryId.count()
        val minimumOrdinal = VoucherPoolEntryTable.sourceOrdinal.min()
        val maximumOrdinal = VoucherPoolEntryTable.sourceOrdinal.max()
        val row = VoucherPoolEntryTable
            .select(entryCount, minimumOrdinal, maximumOrdinal)
            .where {
                (VoucherPoolEntryTable.tenantId eq tenantId) and
                        (VoucherPoolEntryTable.batchId eq batchId)
            }
            .single()
        return BatchOrdinalCoverage(
            entryCount = row[entryCount],
            minimumOrdinal = row[minimumOrdinal],
            maximumOrdinal = row[maximumOrdinal],
        )
    }

    override fun activateBatch(batch: BatchRecord): BatchRecord {
        val updated = VoucherPoolBatchTable.update({
            (VoucherPoolBatchTable.tenantId eq batch.tenantId) and
                    (VoucherPoolBatchTable.batchId eq batch.batchId) and
                    (VoucherPoolBatchTable.revision eq batch.revision) and
                    (VoucherPoolBatchTable.state eq BatchState.STAGING)
        }) {
            it[VoucherPoolBatchTable.state] = BatchState.ACTIVE
            it[VoucherPoolBatchTable.revision] = VoucherPoolBatchTable.revision + 1
            it[VoucherPoolBatchTable.updatedAt] = CurrentTimestamp
        }
        check(updated == 1) { "batch activation lost its revision" }
        return lockBatchForUpdate(batch.tenantId, batch.batchId)
            .checkNotNull { "lock batch does not exist. tenantId=${batch.tenantId}, batchId=${batch.batchId}" }
    }

    override fun updateBatchState(batch: BatchRecord, state: BatchState): BatchRecord {
        val updated = VoucherPoolBatchTable.update({
            (VoucherPoolBatchTable.tenantId eq batch.tenantId) and
                    (VoucherPoolBatchTable.batchId eq batch.batchId) and
                    (VoucherPoolBatchTable.revision eq batch.revision)
        }) {
            it[VoucherPoolBatchTable.state] = state
            it[VoucherPoolBatchTable.revision] = VoucherPoolBatchTable.revision + 1
            it[VoucherPoolBatchTable.updatedAt] = CurrentTimestamp
        }
        check(updated == 1) { "batch state update lost its revision" }
        return lockBatchForUpdate(batch.tenantId, batch.batchId)
            .checkNotNull { "lock batch does not exist. tenantId=${batch.tenantId}, batchId=${batch.batchId}" }
    }

    override fun lockCampaignForShare(tenantId: String, campaignId: UUID): CampaignRecord =
        lockCampaignForShareOrNull(connection, tenantId, campaignId)
            .checkNotNull { "campaign campaign campaign does not exist. tenantId=$tenantId, campaignId=$campaignId" }

    /** Exposed DSL에 동등한 PostgreSQL `FOR SHARE` 표현이 없어 공유 row lock은 raw로 보존합니다. */
    private fun lockCampaignForShareOrNull(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
    ): CampaignRecord? =
        connection.prepareStatement(
            "SELECT * FROM voucher_pool_campaigns WHERE tenant_id=? AND campaign_id=? FOR SHARE",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, campaignId)
            statement.executeQuery().use { result ->
                if (result.next()) result.campaignRecord()
                else null
            }
        }

    override fun lockBatchForShare(tenantId: String, batchId: UUID): BatchRecord =
        // `FOR SHARE`는 campaign/batch guard의 canonical lock ordering을 보존하기 위한 PostgreSQL 경계입니다.
        connection.prepareStatement(
            "SELECT * FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=? FOR SHARE",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, batchId)
            statement.executeQuery().use { result ->
                if (result.next()) result.batchRecord()
                else error("batch campaign batch does not exist. tenantId=$tenantId, batchId=$batchId")
            }
        }

    override fun lockUserLimit(
        tenantId: String,
        campaignId: UUID,
        userDigest: ByteArray,
    ): UserLimitRecord {
        VoucherPoolUserLimitTable.insertIgnore {
            it[VoucherPoolUserLimitTable.tenantId] = tenantId
            it[VoucherPoolUserLimitTable.campaignId] = campaignId
            it[VoucherPoolUserLimitTable.userDigest] = userDigest.copyOf()
            it[activeReservations] = 0
            it[activeAllocations] = 0
            it[lifetimeConsumed] = 0
            it[revision] = 0
        }
        return VoucherPoolUserLimitTable
            .selectAll()
            .where {
                (VoucherPoolUserLimitTable.tenantId eq tenantId) and
                        (VoucherPoolUserLimitTable.campaignId eq campaignId) and
                        (VoucherPoolUserLimitTable.userDigest eq userDigest)
            }
            .forUpdate()
            .singleOrNull()
            ?.userLimitRecord()
            ?: error("user_digest does not exist. tenantId=$tenantId, campaignId=$campaignId")
    }

    override fun selectAvailableEntrySkipLocked(
        tenantId: String,
        campaignId: UUID,
        lockedBatchIds: List<UUID>,
    ): EntryRecord? {
        if (lockedBatchIds.isEmpty()) return null

        // `ANY(uuid[])`와 `FOR UPDATE OF ... SKIP LOCKED`는 bounded worker 경쟁을 위한 PostgreSQL 전용 경로입니다.
        return connection.prepareStatement(
            """SELECT e.* FROM voucher_pool_entries e JOIN voucher_pool_batches b
              ON b.tenant_id=e.tenant_id AND b.batch_id=e.batch_id AND b.campaign_id=e.campaign_id
            WHERE e.tenant_id=? AND e.campaign_id=? AND e.state='AVAILABLE' AND e.quarantined_at IS NULL
              AND b.state='ACTIVE' AND b.activates_at<=transaction_timestamp()
              AND (b.expires_at IS NULL OR b.expires_at>transaction_timestamp())
              AND b.batch_id=ANY(?)
            ORDER BY b.activates_at,b.batch_id,e.source_ordinal,e.entry_id
            LIMIT 1 FOR UPDATE OF e SKIP LOCKED""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId); statement.setObject(2, campaignId)
            statement.setArray(3, connection.createArrayOf("uuid", lockedBatchIds.toTypedArray()))
            statement.executeQuery().use { result ->
                if (result.next()) result.entryRecord() else null
            }
        }
    }

    override fun lockReservationGuards(
        tenantId: String,
        campaignId: UUID,
        userDigest: ByteArray,
    ): ReservationGuards? {
        val campaign = lockCampaignForShareOrNull(connection, tenantId, campaignId) ?: return null
        val batches = campaignBatchIds(connection, tenantId, campaignId)
            .map { batchId -> lockBatchForShare(tenantId, batchId) }

        return ReservationGuards(campaign, batches, lockUserLimit(tenantId, campaignId, userDigest))
    }

    override fun hasAvailableEligibleEntry(
        tenantId: String,
        campaignId: UUID,
        lockedBatchIds: List<UUID>,
    ): Boolean {
        if (lockedBatchIds.isEmpty()) return false
        val joined = VoucherPoolEntryTable.innerJoin(
            VoucherPoolBatchTable,
            { VoucherPoolEntryTable.tenantId },
            { VoucherPoolBatchTable.tenantId },
            additionalConstraint = {
                (VoucherPoolEntryTable.batchId eq VoucherPoolBatchTable.batchId) and
                        (VoucherPoolEntryTable.campaignId eq VoucherPoolBatchTable.campaignId)
            },
        )
        return joined
            .select(VoucherPoolEntryTable.entryId)
            .where {
                (VoucherPoolEntryTable.tenantId eq tenantId) and
                        (VoucherPoolEntryTable.campaignId eq campaignId) and
                        (VoucherPoolEntryTable.state eq EntryState.AVAILABLE) and
                        VoucherPoolEntryTable.quarantinedAt.isNull() and
                        (VoucherPoolBatchTable.state eq BatchState.ACTIVE) and
                        (VoucherPoolBatchTable.activatesAt lessEq CurrentTimestamp) and
                        (VoucherPoolBatchTable.expiresAt.isNull() or
                                (VoucherPoolBatchTable.expiresAt greater CurrentTimestamp)) and
                        (VoucherPoolBatchTable.batchId inList lockedBatchIds)
            }
            .limit(1)
            .singleOrNull() != null
    }

    override fun createReservation(
        reservation: ReservationRecord,
        entry: EntryRecord,
        userLimit: UserLimitRecord,
    ): ReservationRecord {
        // entry·reservation·user-limit·depth를 canonical 순서로 묶고 transaction_timestamp()를 공유해야 하므로 raw를 유지합니다.
        connection.prepareStatement(
            """UPDATE voucher_pool_entries SET state='RESERVED',reservation_id=?,user_digest=?,reserved_at=transaction_timestamp(),
                  reservation_expires_at=?,revision=revision+1 WHERE tenant_id=? AND entry_id=? AND revision=? AND state='AVAILABLE'""".trimIndent(),
        ).use { statement ->
            statement.setObject(1, reservation.reservationId)
            statement.setBytes(2, reservation.userDigest.copyBytes())
            statement.setTimestamp(3, Timestamp.from(reservation.expiresAt))
            statement.setString(4, reservation.tenantId)
            statement.setObject(5, entry.entryId)
            statement.setLong(6, entry.revision)

            check(statement.executeUpdate() == 1) { "reservation entry transition lost its revision" }
        }

        transitionPoolDepth(connection, entry.tenantId, entry.batchId, EntryState.AVAILABLE, EntryState.RESERVED)
        insertReservation(connection, reservation)
        updateUserLimit(connection, userLimit, reservationsDelta = 1, allocationsDelta = 0, lifetimeDelta = 0)

        return lockReservationById(connection, reservation.tenantId, reservation.reservationId)
            .checkNotNull { "Not found locked reservation. tenantId=${reservation.tenantId}, reservationId=${reservation.reservationId}" }
    }

    override fun lockReservationChain(
        tenantId: String,
        reservationId: UUID,
        userDigest: ByteArray,
    ): LockedReservationChain? {
        // customer chain은 FOR SHARE와 후속 row lock의 canonical ordering을 유지해야 하므로 raw lock helpers를 사용합니다.
        val hint = findReservation(connection, tenantId, reservationId) ?: return null

        if (!MessageDigest.isEqual(hint.userDigest.copyBytes(), userDigest)) return null
        val campaign = lockCampaignForShare(tenantId, hint.campaignId)
        val batch = lockBatchForShare(tenantId, hint.batchId)
        val limit = lockUserLimit(tenantId, hint.campaignId, userDigest)
        val reservation = lockReservationById(connection, tenantId, reservationId) ?: return null
        check(MessageDigest.isEqual(reservation.userDigest.copyBytes(), userDigest))

        val entry = lockEntry(connection, tenantId, reservation.entryId)
        return LockedReservationChain(campaign, batch, limit, reservation, entry)
    }

    override fun releaseReservation(chain: LockedReservationChain): ReservationRecord {
        check(chain.reservation.state == "ACTIVE" && chain.entry.state == EntryState.RESERVED)
        updateReservationState(connection, chain.reservation, "RELEASED")
        connection.prepareStatement(
            """UPDATE voucher_pool_entries SET state='AVAILABLE',reservation_id=NULL,user_digest=NULL,reserved_at=NULL,
                  reservation_expires_at=NULL,revision=revision+1 WHERE tenant_id=? AND entry_id=? AND revision=? AND state='RESERVED'""".trimIndent(),
        ).use { statement ->
            statement.setString(1, chain.entry.tenantId)
            statement.setObject(2, chain.entry.entryId)
            statement.setLong(3, chain.entry.revision)
            check(statement.executeUpdate() == 1)
        }
        transitionPoolDepth(
            connection,
            chain.entry.tenantId,
            chain.entry.batchId,
            EntryState.RESERVED,
            EntryState.AVAILABLE
        )
        updateUserLimit(connection, chain.userLimit, -1, 0, 0)

        return lockReservationById(
            connection,
            chain.reservation.tenantId,
            chain.reservation.reservationId
        ).checkNotNull {
            "lock reservation does not exist. tenantId=${chain.reservation.tenantId}, reservationId=${chain.reservation.tenantId}"
        }

    }

    override fun allocateReservation(
        chain: LockedReservationChain,
        allocation: AllocationRecord,
        verificationDigest: ByteArray,
        verificationKeyVersion: Int,
    ): LockedAllocationChain {
        // allocation은 reservation·entry·depth·user-limit을 한 transaction 순서로 갱신하며 DB transaction time을 사용합니다.
        check(chain.reservation.state == "ACTIVE" && chain.entry.state == EntryState.RESERVED)
        connection.prepareStatement(
            """INSERT INTO voucher_pool_allocations
                (tenant_id,allocation_id,reservation_id,campaign_id,batch_id,entry_id,user_digest,entitlement_root_id,
                 replacement_ordinal,allocation_expires_at,policy_version,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,0)""".trimIndent(),
        ).use { statement ->
            statement.setString(1, allocation.tenantId)
            statement.setObject(2, allocation.allocationId)
            statement.setObject(3, allocation.reservationId)
            statement.setObject(4, allocation.campaignId)
            statement.setObject(5, allocation.batchId)
            statement.setObject(6, allocation.entryId)
            statement.setBytes(7, allocation.userDigest.copyBytes())
            statement.setObject(8, allocation.entitlementRootId)
            statement.setInt(9, allocation.replacementOrdinal)
            statement.setTimestamp(10, Timestamp.from(allocation.expiresAt))
            statement.setLong(11, allocation.policyVersion); statement.executeUpdate()
        }
        updateReservationState(connection, chain.reservation, "ALLOCATED")

        connection.prepareStatement(
            """UPDATE voucher_pool_entries SET state='ALLOCATED',allocation_id=?,allocated_at=transaction_timestamp(),
                  allocation_expires_at=?,allocation_policy_version=?,entitlement_root_id=?,replacement_count=?,
                  verification_digest=?,verification_key_version=?,revision=revision+1
                WHERE tenant_id=? AND entry_id=? AND revision=? AND state='RESERVED'""".trimIndent(),
        ).use { statement ->
            statement.setObject(1, allocation.allocationId)
            statement.setTimestamp(2, Timestamp.from(allocation.expiresAt))
            statement.setLong(3, allocation.policyVersion)
            statement.setObject(4, allocation.entitlementRootId)
            statement.setInt(5, allocation.replacementOrdinal)
            statement.setBytes(6, verificationDigest)
            statement.setInt(7, verificationKeyVersion)
            statement.setString(8, allocation.tenantId)
            statement.setObject(9, allocation.entryId)
            statement.setLong(10, chain.entry.revision)

            check(statement.executeUpdate() == 1) { "allocation entry transition lost its revision" }
        }
        transitionPoolDepth(
            connection,
            chain.entry.tenantId,
            chain.entry.batchId,
            EntryState.RESERVED,
            EntryState.ALLOCATED
        )
        updateUserLimit(
            connection = connection,
            limit = chain.userLimit,
            reservationsDelta = -1,
            allocationsDelta = 1,
            lifetimeDelta = if (allocation.replacementOrdinal == 0) 1 else 0
        )
        return lockAllocationChain(
            allocation.tenantId,
            allocation.allocationId,
            allocation.userDigest.copyBytes()
        ).checkNotNull {
            "lock allocation does not found. tenant_id=${chain.entry.tenantId}, reservation_id=${chain.reservation.tenantId}"
        }
    }

    override fun lockAllocationChain(
        tenantId: String,
        allocationId: UUID,
        userDigest: ByteArray?,
    ): LockedAllocationChain? {
        val hint = findAllocation(connection, tenantId, allocationId) ?: return null
        if (userDigest != null && !MessageDigest.isEqual(hint.userDigest.copyBytes(), userDigest)) return null
        val campaign = lockCampaignForShare(tenantId, hint.campaignId)
        val batch = lockBatchForShare(tenantId, hint.batchId)
        val digest = userDigest ?: hint.userDigest.copyBytes()
        val limit = lockUserLimit(tenantId, hint.campaignId, digest)
        val reservation = lockReservationById(connection, tenantId, hint.reservationId) ?: return null
        check(MessageDigest.isEqual(reservation.userDigest.copyBytes(), digest))
        val entry = lockEntry(connection, tenantId, hint.entryId)
        val allocation = lockAllocationById(connection, tenantId, allocationId) ?: return null
        return LockedAllocationChain(campaign, batch, limit, reservation, allocation, entry)
    }

    override fun lockReplacementChain(
        tenantId: String,
        allocationId: UUID,
        userDigest: ByteArray,
    ): LockedReplacementChain? {
        // replacement candidate는 SKIP LOCKED와 depth lock ordering을 함께 요구하므로 raw PostgreSQL 경계를 유지합니다.
        val hint = findAllocation(connection, tenantId, allocationId) ?: return null
        if (!MessageDigest.isEqual(hint.userDigest.copyBytes(), userDigest)) return null
        val campaign = lockCampaignForShare(tenantId, hint.campaignId)
        val batchIds = eligibleBatchIds(tenantId, hint.campaignId)
        val batches = batchIds.mapNotNull { lockEligibleBatchForShare(connection, tenantId, hint.campaignId, it) }
        val lockedBatchIds = batches.map(BatchRecord::batchId)
        val originalBatch =
            batches.firstOrNull { it.batchId == hint.batchId } ?: lockBatchForShare(tenantId, hint.batchId)
        val limit = lockUserLimit(tenantId, hint.campaignId, userDigest)
        val reservation = lockReservationById(connection, tenantId, hint.reservationId) ?: return null

        check(MessageDigest.isEqual(reservation.userDigest.copyBytes(), userDigest))

        val entry = lockEntry(connection, tenantId, hint.entryId)
        val allocation = lockAllocationById(connection, tenantId, allocationId) ?: return null
        val original = LockedAllocationChain(campaign, originalBatch, limit, reservation, allocation, entry)
        val candidate = selectAvailableEntrySkipLocked(tenantId, hint.campaignId, lockedBatchIds)

        return LockedReplacementChain(
            original = original,
            candidate = candidate,
            availableCandidateExists = candidate != null || hasAvailableEligibleEntry(
                tenantId,
                hint.campaignId,
                lockedBatchIds
            ),
        )
    }

    override fun replaceLostReveal(
        chain: LockedReplacementChain,
        reservation: ReservationRecord,
    ): ReservationRecord {
        val candidate = checkNotNull(chain.candidate)
        val original = chain.original
        check(original.entry.state == EntryState.ALLOCATED && original.entry.revealedAt != null)

        connection.prepareStatement(
            """UPDATE voucher_pool_entries SET state='REVOKED',terminal_reason='LOST_REVEAL',revision=revision+1
                WHERE tenant_id=? AND entry_id=? AND revision=? AND state='ALLOCATED'""".trimIndent(),
        ).use { statement ->
            statement.setString(1, original.entry.tenantId)
            statement.setObject(2, original.entry.entryId)
            statement.setLong(3, original.entry.revision)
            check(statement.executeUpdate() == 1)
        }
        advanceAllocationRevision(original.allocation)

        connection.prepareStatement(
            """UPDATE voucher_pool_entries SET state='RESERVED',reservation_id=?,user_digest=?,reserved_at=transaction_timestamp(),
                  reservation_expires_at=?,revision=revision+1 WHERE tenant_id=? AND entry_id=? AND revision=? AND state='AVAILABLE'""".trimIndent(),
        ).use { statement ->
            statement.setObject(1, reservation.reservationId)
            statement.setBytes(2, reservation.userDigest.copyBytes())
            statement.setTimestamp(3, Timestamp.from(reservation.expiresAt))
            statement.setString(4, reservation.tenantId)
            statement.setObject(5, candidate.entryId)
            statement.setLong(6, candidate.revision)
            check(statement.executeUpdate() == 1)
        }
        transitionReplacementPoolDepth(connection, original.entry, candidate)
        insertReservation(connection, reservation)
        updateUserLimit(connection, original.userLimit, 1, -1, 0)

        return lockReservationById(connection, reservation.tenantId, reservation.reservationId)
            .checkNotNull {
                "lock reservation does not exist. tenant_id=${reservation.tenantId}, reservation_id=${reservation.reservationId}"
            }
    }

    override fun transitionAllocationTerminal(
        chain: LockedAllocationChain,
        state: EntryState,
        reason: String,
    ): LockedAllocationChain {
        require(state == EntryState.REDEEMED || state == EntryState.RELEASED || state == EntryState.REVOKED)

        connection.prepareStatement(
            """UPDATE voucher_pool_entries SET state=?,terminal_reason=?,redeemed_at=CASE WHEN ?='REDEEMED' THEN transaction_timestamp() ELSE redeemed_at END,
                  revision=revision+1 WHERE tenant_id=? AND entry_id=? AND revision=? AND state='ALLOCATED'""".trimIndent(),
        ).use { statement ->
            statement.setString(1, state.name)
            statement.setString(2, reason)
            statement.setString(3, state.name)
            statement.setString(4, chain.entry.tenantId)
            statement.setObject(5, chain.entry.entryId)
            statement.setLong(6, chain.entry.revision)
            check(statement.executeUpdate() == 1)
        }
        transitionPoolDepth(connection, chain.entry.tenantId, chain.entry.batchId, EntryState.ALLOCATED, state)
        connection.prepareStatement(
            "UPDATE voucher_pool_allocations SET revision=revision+1 WHERE tenant_id=? AND allocation_id=? AND revision=?",
        ).use { statement ->
            statement.setString(1, chain.allocation.tenantId)
            statement.setObject(2, chain.allocation.allocationId)
            statement.setLong(3, chain.allocation.revision)
            check(statement.executeUpdate() == 1)
        }

        updateUserLimit(connection, chain.userLimit, 0, -1, 0)

        return lockAllocationChain(
            chain.allocation.tenantId,
            chain.allocation.allocationId,
            chain.allocation.userDigest.copyBytes()
        ).checkNotNull { "lock allocation chain does not exist. tenant_id=${chain.allocation.tenantId}, allocation_id=${chain.allocation.allocationId}" }
    }

    override fun lockReservedCryptoEntry(
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        entryId: UUID,
        sourceOrdinal: Long,
        expectedRevision: Long,
    ): LockedVoucherCryptoRecord? =
        lockCryptoEntry(
            connection = connection,
            tenantId = tenantId,
            campaignId = campaignId,
            batchId = batchId,
            entryId = entryId,
            sourceOrdinal = sourceOrdinal,
            expectedRevision = expectedRevision,
            requiredState = "RESERVED",
        )

    @Suppress("ReturnCount") // Each stale revision must stop before a lower-order row is locked.
    override fun lockCanonicalChain(candidate: WorkerCandidate): LockedWorkerChain? {
        val campaign = lockCampaignForUpdate(candidate) ?: return null
        val batch = lockBatchForUpdate(candidate) ?: return null
        val userLimits = lockExpectedUserLimits(connection, candidate) ?: return null
        val reservations = lockExpectedReservations(connection, candidate) ?: return null
        val entry = lockEntryForUpdate(connection, candidate) ?: return null

        return LockedWorkerChain(campaign, batch, entry, userLimits, reservations)
    }

    override fun expireReservation(chain: LockedWorkerChain): Boolean {
        val reservation = chain.reservations.singleOrNull() ?: return false
        val userLimit = chain.userLimits.singleOrNull() ?: return false
        if (
            chain.entry.state != EntryState.RESERVED || reservation.state != "ACTIVE" ||
            reservation.expiresAt > transactionTime()
        ) {
            return false
        }
        updateReservationState(connection, reservation, "EXPIRED")
        connection.prepareStatement(
            """UPDATE voucher_pool_entries SET state='AVAILABLE',reservation_id=NULL,user_digest=NULL,reserved_at=NULL,
                  reservation_expires_at=NULL,revision=revision+1
                WHERE tenant_id=? AND entry_id=? AND revision=? AND state='RESERVED'""".trimIndent(),
        ).use { statement ->
            statement.setString(1, chain.entry.tenantId)
            statement.setObject(2, chain.entry.entryId)
            statement.setLong(3, chain.entry.revision)
            check(statement.executeUpdate() == 1)
        }
        transitionPoolDepth(
            connection,
            chain.entry.tenantId,
            chain.entry.batchId,
            EntryState.RESERVED,
            EntryState.AVAILABLE
        )
        updateUserLimit(connection, userLimit, -1, 0, 0)
        appendAudit(
            VoucherPoolAuditRecord(
                tenantId = reservation.tenantId,
                campaignId = reservation.campaignId,
                aggregateType = "RESERVATION",
                aggregateId = reservation.reservationId,
                revision = reservation.revision + 1,
                policyVersion = reservation.policyVersion,
                actorType = "WORKER",
                reasonCode = "RESERVATION_EXPIRED",
            ),
        )
        return true
    }

    override fun terminalizeWorkerEntry(
        chain: LockedWorkerChain,
        targetState: EntryState,
        reasonCode: String,
    ): Boolean {
        require(targetState == EntryState.REVOKED || targetState == EntryState.EXPIRED)
        if (chain.entry.state !in setOf(EntryState.AVAILABLE, EntryState.RESERVED, EntryState.ALLOCATED)) return false
        if (reasonCode == "ALLOCATION_EXPIRED" && chain.entry.state == EntryState.ALLOCATED) {
            val expiresAt = chain.entry.allocationExpiresAt ?: return false
            if (expiresAt > transactionTime()) return false
        }
        val audit = when (chain.entry.state) {
            EntryState.AVAILABLE -> workerEntryAudit(chain, reasonCode)
            EntryState.RESERVED -> terminalizeWorkerReservation(connection, chain, targetState, reasonCode)
            EntryState.ALLOCATED -> terminalizeWorkerAllocation(connection, chain, targetState, reasonCode)
            else                -> return false
        }
        connection.prepareStatement(
            """UPDATE voucher_pool_entries SET state=?,terminal_reason=?,revision=revision+1
                WHERE tenant_id=? AND entry_id=? AND revision=? AND state=?""".trimIndent(),
        ).use { statement ->
            statement.setString(1, targetState.name)
            statement.setString(2, reasonCode)
            statement.setString(3, chain.entry.tenantId)
            statement.setObject(4, chain.entry.entryId)
            statement.setLong(5, chain.entry.revision)
            statement.setString(6, chain.entry.state.name)
            check(statement.executeUpdate() == 1)
        }
        transitionPoolDepth(connection, chain.entry.tenantId, chain.entry.batchId, chain.entry.state, targetState)
        appendAudit(audit)
        return true
    }

    override fun appendAudit(event: VoucherPoolAuditRecord) {
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
            it[beforeCount] = event.beforeCount
            it[afterCount] = event.afterCount
        }
    }

    override fun lockAllocatedCryptoEntry(
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        entryId: UUID,
        sourceOrdinal: Long,
        expectedRevision: Long,
    ): LockedVoucherCryptoRecord? = lockCryptoEntry(
        connection, tenantId, campaignId, batchId, entryId, sourceOrdinal, expectedRevision, "ALLOCATED",
    )

    private fun lockCryptoEntry(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        entryId: UUID,
        sourceOrdinal: Long,
        expectedRevision: Long,
        requiredState: String,
    ): LockedVoucherCryptoRecord? =
        connection.prepareStatement(
            """SELECT e.state,e.revision,e.stable_dedup_digest,d.key_version AS stable_dedup_key_version,
            e.code_ciphertext,e.code_nonce,e.wrapped_dek,e.wrap_nonce,e.kek_version
            FROM voucher_pool_entries e JOIN voucher_pool_code_dedup d
              ON d.tenant_id=e.tenant_id AND d.stable_dedup_digest=e.stable_dedup_digest
            WHERE e.tenant_id=? AND e.campaign_id=? AND e.batch_id=? AND e.entry_id=? AND e.source_ordinal=?
              AND e.revision=? AND e.state=?
              AND e.revealed_at IS NULL AND e.quarantined_at IS NULL FOR UPDATE OF e""".trimIndent(),
        ).use { statement ->
            var parameterIndex = 1
            statement.setString(parameterIndex++, tenantId)
            statement.setObject(parameterIndex++, campaignId)
            statement.setObject(parameterIndex++, batchId)
            statement.setObject(parameterIndex++, entryId)
            statement.setLong(parameterIndex++, sourceOrdinal)
            statement.setLong(parameterIndex++, expectedRevision)
            statement.setString(parameterIndex, requiredState)
            statement.executeQuery().use { result ->
                if (result.next()) result.lockedVoucherCryptoRecord() else null
            }
        }

    override fun eraseVoucherCiphertext(
        tenantId: String,
        entryId: UUID,
        expectedRevision: Long,
    ) {
        val whereOp = {
            (VoucherPoolEntryTable.tenantId eq tenantId) and
                    (VoucherPoolEntryTable.entryId eq entryId) and
                    (VoucherPoolEntryTable.revision eq expectedRevision) and
                    VoucherPoolEntryTable.quarantinedAt.isNull()
        }
        val updated = VoucherPoolEntryTable.update(whereOp) {
            it[revealedAt] = CurrentTimestamp
            it[codeCiphertext] = null
            it[codeNonce] = null
            it[wrappedDek] = null
            it[wrapNonce] = null
            it[kekVersion] = null
            it[revision] = expectedRevision + 1
        }
        check(updated == 1) { "voucher ciphertext erase lost its revision" }
    }

    override fun advanceAllocationRevision(
        allocation: AllocationRecord,
    ): AllocationRecord {
        connection.prepareStatement(
            "UPDATE voucher_pool_allocations SET revision=revision+1 WHERE tenant_id=? AND allocation_id=? AND revision=?",
        ).use { statement ->
            statement.setString(1, allocation.tenantId)
            statement.setObject(2, allocation.allocationId)
            statement.setLong(3, allocation.revision)
            check(statement.executeUpdate() == 1) { "allocation revision advance lost its revision" }
        }
        return lockAllocationById(connection, allocation.tenantId, allocation.allocationId)
            .checkNotNull { "lock allocation does not exist. tenantId=${allocation.tenantId}, allocationId=${allocation.allocationId}" }
    }

    override fun quarantineVoucherCrypto(
        tenantId: String,
        entryId: UUID,
        sourceState: EntryState,
        sourceRevision: Long,
        reasonCode: String,
    ) {
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

    internal fun lockEntry(connection: Connection, tenantId: String, entryId: UUID): EntryRecord =
        connection.prepareStatement("SELECT * FROM voucher_pool_entries WHERE tenant_id=? AND entry_id=? FOR UPDATE")
            .use { statement ->
                statement.setString(1, tenantId)
                statement.setObject(2, entryId)

                statement.executeQuery().use { result ->
                    check(result.next())
                    result.entryRecord()
                }
            }

    internal fun selectWorkerCandidates(
        tenantId: String,
        batchId: UUID,
        limit: Int,
    ): List<WorkerCandidate> {
        val batch = VoucherPoolBatchTable
            .select(
                VoucherPoolBatchTable.campaignId,
                VoucherPoolBatchTable.revision,
            ).where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and (VoucherPoolBatchTable.batchId eq batchId)
            }
            .singleOrNull() ?: return emptyList()

        val campaignId = batch[VoucherPoolBatchTable.campaignId]
        val campaignRevision = VoucherPoolCampaignTable
            .select(VoucherPoolCampaignTable.revision)
            .where {
                (VoucherPoolCampaignTable.tenantId eq tenantId) and
                        (VoucherPoolCampaignTable.campaignId eq campaignId)
            }
            .single()[VoucherPoolCampaignTable.revision]

        return workerEntryQuery(tenantId, batchId, limit).map { row ->
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

    internal fun poolDepth(tenantId: String, batchId: UUID): Map<EntryState, Long> {
        val (query, depth) = poolDepthQuery(tenantId, batchId)
        return query.associate { it[VoucherPoolDepthTable.state] to it[depth].checkNotNull("depth") }
    }

    internal fun workerCandidatePlanSql(tenantId: String, batchId: UUID, limit: Int): String =
        workerEntryQuery(tenantId, batchId, limit).prepareSQL(TransactionManager.current(), false)

    internal fun poolDepthPlanSql(tenantId: String, batchId: UUID): String =
        poolDepthQuery(tenantId, batchId).first.prepareSQL(TransactionManager.current(), false)

    private fun workerEntryQuery(tenantId: String, batchId: UUID, limit: Int) =
        VoucherPoolEntryTable
            .select(
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
            }
            .orderBy(
                VoucherPoolEntryTable.sourceOrdinal to SortOrder.ASC,
                VoucherPoolEntryTable.entryId to SortOrder.ASC,
            )
            .limit(limit)

    private fun poolDepthQuery(tenantId: String, batchId: UUID): Pair<Query, Sum<Long>> {
        val depth = VoucherPoolDepthTable.entryCount.sum()
        return VoucherPoolDepthTable
            .select(VoucherPoolDepthTable.state, depth)
            .where { (VoucherPoolDepthTable.tenantId eq tenantId) and (VoucherPoolDepthTable.batchId eq batchId) }
            .groupBy(VoucherPoolDepthTable.state)
            .orderBy(VoucherPoolDepthTable.state, SortOrder.ASC) to depth
    }

    private fun transitionPoolDepth(
        connection: Connection,
        tenantId: String,
        batchId: UUID,
        from: EntryState,
        to: EntryState,
    ) {
        // depth projection은 두 state row를 순서대로 잠그고 음수 방지를 원자적으로 검증해야 합니다.
        val locked = connection
            .prepareStatement(
                """SELECT state FROM voucher_pool_pool_depth
                WHERE tenant_id=? AND batch_id=? AND state IN (?,?) ORDER BY state FOR UPDATE""".trimIndent(),
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.setObject(2, batchId)
                statement.setString(3, from.name)
                statement.setString(4, to.name)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(result.getString(1))
                        }
                    }
                }
            }
        check(locked.size == 2) { "pool depth transition projections are missing" }
        updatePoolDepth(connection, tenantId, batchId, from, -1)
        updatePoolDepth(connection, tenantId, batchId, to, 1)
    }

    private fun ensurePoolDepthRows(connection: Connection, tenantId: String, batchId: UUID) {
        EntryState.entries.sortedBy(EntryState::name).forEach { state ->
            connection.prepareStatement(
                """INSERT INTO voucher_pool_pool_depth(tenant_id,batch_id,state,entry_count,revision)
                    VALUES (?,?,?,0,0) ON CONFLICT (tenant_id,batch_id,state) DO NOTHING""".trimIndent(),
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.setObject(2, batchId)
                statement.setString(3, state.name)
                statement.executeUpdate()
            }
        }
    }

    private fun transitionReplacementPoolDepth(
        connection: Connection,
        original: EntryRecord,
        candidate: EntryRecord,
    ) {
        val batchIds = setOf(original.batchId, candidate.batchId).sortedBy(UUID::toString)
        val locked = connection.prepareStatement(
            """SELECT batch_id,state FROM voucher_pool_pool_depth
                WHERE tenant_id=? AND batch_id=ANY(?) ORDER BY batch_id,state FOR UPDATE""".trimIndent(),
        ).use { statement ->
            statement.setString(1, original.tenantId)
            statement.setArray(2, connection.createArrayOf("uuid", batchIds.toTypedArray()))
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(Unit)
                    }
                }
            }
        }
        check(locked.size == batchIds.size * EntryState.entries.size) {
            "replacement pool depth projections are missing"
        }
        updatePoolDepth(connection, original.tenantId, original.batchId, EntryState.ALLOCATED, -1)
        updatePoolDepth(connection, original.tenantId, original.batchId, EntryState.REVOKED, 1)
        updatePoolDepth(connection, candidate.tenantId, candidate.batchId, EntryState.AVAILABLE, -1)
        updatePoolDepth(connection, candidate.tenantId, candidate.batchId, EntryState.RESERVED, 1)
    }

    private fun updatePoolDepth(
        connection: Connection,
        tenantId: String,
        batchId: UUID,
        state: EntryState,
        delta: Long,
    ) {
        val updated = connection.prepareStatement(
            """UPDATE voucher_pool_pool_depth SET entry_count=entry_count+?,revision=revision+1
                WHERE tenant_id=? AND batch_id=? AND state=? AND entry_count+?>=0""".trimIndent(),
        ).use { statement ->
            statement.setLong(1, delta)
            statement.setString(2, tenantId)
            statement.setObject(3, batchId)
            statement.setString(4, state.name)
            statement.setLong(5, delta)
            statement.executeUpdate()
        }
        check(updated == 1) { "pool depth projection would become negative" }
    }

    private fun incrementPoolDepth(
        connection: Connection,
        tenantId: String,
        batchId: UUID,
        state: EntryState,
        count: Long,
    ) {
        connection.prepareStatement(
            """INSERT INTO voucher_pool_pool_depth(tenant_id,batch_id,state,entry_count,revision)
                VALUES (?,?,?,?,0) ON CONFLICT (tenant_id,batch_id,state) DO UPDATE
                SET entry_count=voucher_pool_pool_depth.entry_count+EXCLUDED.entry_count,
                    revision=voucher_pool_pool_depth.revision+1""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, batchId)
            statement.setString(3, state.name)
            statement.setLong(4, count)

            statement.executeUpdate()
        }
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

    private fun lockCampaignForUpdate(candidate: WorkerCandidate): CampaignRecord? =
        VoucherPoolCampaignTable
            .selectAll()
            .where {
                (VoucherPoolCampaignTable.tenantId eq candidate.tenantId) and
                        (VoucherPoolCampaignTable.campaignId eq candidate.campaignId) and
                        (VoucherPoolCampaignTable.revision eq candidate.expectedCampaignRevision)
            }
            .forUpdate()
            .singleOrNull()
            ?.campaignRecord()

    private fun eligibleBatchIds(tenantId: String, campaignId: UUID): List<UUID> =
        VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.batchId)
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
            ).map {
                it[VoucherPoolBatchTable.batchId]
            }

    private fun campaignBatchIds(connection: Connection, tenantId: String, campaignId: UUID): List<UUID> =
        VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.batchId)
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.campaignId eq campaignId)
            }
            .orderBy(VoucherPoolBatchTable.batchId to SortOrder.ASC)
            .map { it[VoucherPoolBatchTable.batchId] }

    private fun lockEligibleBatchForShare(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
    ): BatchRecord? =
        connection.prepareStatement(
            """SELECT * FROM voucher_pool_batches WHERE tenant_id=? AND campaign_id=? AND batch_id=? AND state='ACTIVE'
            AND activates_at<=transaction_timestamp()
            AND (expires_at IS NULL OR expires_at>transaction_timestamp()) FOR SHARE""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, campaignId)
            statement.setObject(3, batchId)
            statement.executeQuery().use { result ->
                if (result.next()) result.batchRecord() else null
            }
        }

    private fun lockBatchForUpdate(candidate: WorkerCandidate): BatchRecord? =
        VoucherPoolBatchTable
            .selectAll()
            .where {
                (VoucherPoolBatchTable.tenantId eq candidate.tenantId) and
                        (VoucherPoolBatchTable.batchId eq candidate.batchId) and
                        (VoucherPoolBatchTable.campaignId eq candidate.campaignId) and
                        (VoucherPoolBatchTable.revision eq candidate.expectedBatchRevision)
            }
            .forUpdate()
            .singleOrNull()
            ?.batchRecord()

    private fun lockExistingUserLimit(
        connection: Connection,
        candidate: WorkerCandidate,
        expected: ExpectedUserLimit,
    ): UserLimitRecord? = connection.prepareStatement(
        """SELECT * FROM voucher_pool_user_limits WHERE tenant_id=? AND campaign_id=?
            AND user_digest=? AND revision=? FOR UPDATE""".trimIndent(),
    ).use { statement ->
        statement.setString(1, candidate.tenantId)
        statement.setObject(2, candidate.campaignId)
        statement.setBytes(3, expected.userDigest.copyBytes())
        statement.setLong(4, expected.expectedRevision)
        statement.executeQuery().use { result ->
            if (!result.next()) null
            else UserLimitRecord(
                tenantId = candidate.tenantId,
                campaignId = candidate.campaignId,
                userDigest = DigestValue.of(result.getBytes("user_digest")),
                activeReservations = result.getInt("active_reservations"),
                activeAllocations = result.getInt("active_allocations"),
                lifetimeConsumed = result.getInt("lifetime_consumed"),
                revision = result.getLong("revision"),
            )
        }
    }

    private fun lockReservation(
        connection: Connection,
        candidate: WorkerCandidate,
        expected: ExpectedReservation,
    ): ReservationRecord? =
        connection.prepareStatement(
            """SELECT * FROM voucher_pool_reservations
            WHERE tenant_id=? AND reservation_id=? AND campaign_id=? AND batch_id=? AND entry_id=?
              AND revision=? FOR UPDATE""".trimIndent(),
        ).use { statement ->
            statement.setString(1, candidate.tenantId)
            statement.setObject(2, expected.reservationId)
            statement.setObject(3, candidate.campaignId)
            statement.setObject(4, candidate.batchId)
            statement.setObject(5, candidate.entryId)
            statement.setLong(6, expected.expectedRevision)

            statement.executeQuery().use { result ->
                if (result.next()) result.reservationRecord() else null
            }
        }

    private fun lockEntryForUpdate(connection: Connection, candidate: WorkerCandidate): EntryRecord? =
        connection.prepareStatement(
            """SELECT * FROM voucher_pool_entries WHERE tenant_id=? AND entry_id=? AND campaign_id=?
                AND batch_id=? AND revision=? FOR UPDATE""".trimIndent(),
        ).use { statement ->
            statement.setString(1, candidate.tenantId)
            statement.setObject(2, candidate.entryId)
            statement.setObject(3, candidate.campaignId)
            statement.setObject(4, candidate.batchId)
            statement.setLong(5, candidate.expectedEntryRevision)

            statement.executeQuery().use { result ->
                if (result.next()) result.entryRecord() else null
            }
        }

    private fun findReservation(connection: Connection, tenantId: String, reservationId: UUID): ReservationRecord? =
        connection.prepareStatement(
            "SELECT * FROM voucher_pool_reservations WHERE tenant_id=? AND reservation_id=?",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, reservationId)

            statement.executeQuery().use { result ->
                if (result.next()) result.reservationRecord() else null
            }
        }

    private fun lockReservationById(connection: Connection, tenantId: String, reservationId: UUID): ReservationRecord? =
        connection.prepareStatement(
            "SELECT * FROM voucher_pool_reservations WHERE tenant_id=? AND reservation_id=? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, reservationId)

            statement.executeQuery().use { result ->
                if (result.next()) result.reservationRecord() else null
            }
        }

    private fun findAllocation(connection: Connection, tenantId: String, allocationId: UUID): AllocationRecord? =
        connection.prepareStatement(
            "SELECT * FROM voucher_pool_allocations WHERE tenant_id=? AND allocation_id=?",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, allocationId)

            statement.executeQuery().use { result ->
                if (result.next()) result.allocationRecord() else null
            }
        }

    private fun lockAllocationById(connection: Connection, tenantId: String, allocationId: UUID): AllocationRecord? =
        connection.prepareStatement(
            "SELECT * FROM voucher_pool_allocations WHERE tenant_id=? AND allocation_id=? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setObject(2, allocationId)

            statement.executeQuery().use { result ->
                if (result.next()) result.allocationRecord() else null
            }
        }

    private fun insertReservation(connection: Connection, reservation: ReservationRecord) {
        connection.prepareStatement(
            """INSERT INTO voucher_pool_reservations
                (tenant_id,reservation_id,campaign_id,batch_id,entry_id,user_digest,idempotency_owner_digest,state,
                 reservation_expires_at,entitlement_root_id,replacement_ordinal,policy_version,revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0)""".trimIndent(),
        ).use { statement ->
            statement.setString(1, reservation.tenantId)
            statement.setObject(2, reservation.reservationId)
            statement.setObject(3, reservation.campaignId)
            statement.setObject(4, reservation.batchId)
            statement.setObject(5, reservation.entryId)
            statement.setBytes(6, reservation.userDigest.copyBytes())
            statement.setBytes(7, reservation.idempotencyOwnerDigest.copyBytes())
            statement.setString(8, reservation.state)
            statement.setTimestamp(9, Timestamp.from(reservation.expiresAt))
            statement.setObject(10, reservation.entitlementRootId)
            statement.setInt(11, reservation.replacementOrdinal)
            statement.setLong(12, reservation.policyVersion)

            statement.executeUpdate()
        }
    }

    private fun workerEntryAudit(chain: LockedWorkerChain, reasonCode: String): VoucherPoolAuditRecord =
        VoucherPoolAuditRecord(
            tenantId = chain.entry.tenantId,
            campaignId = chain.entry.campaignId,
            aggregateType = "ENTRY",
            aggregateId = chain.entry.entryId,
            revision = chain.entry.revision + 1,
            policyVersion = chain.batch.policyVersion,
            actorType = "WORKER",
            reasonCode = reasonCode,
        )

    private fun terminalizeWorkerReservation(
        connection: Connection,
        chain: LockedWorkerChain,
        targetState: EntryState,
        reasonCode: String,
    ): VoucherPoolAuditRecord {
        val reservation = checkNotNull(chain.reservations.singleOrNull())
        val userLimit = checkNotNull(chain.userLimits.singleOrNull())
        check(reservation.state == "ACTIVE")

        updateReservationState(connection, reservation, targetState.name)
        updateUserLimit(connection, userLimit, -1, 0, 0)

        return VoucherPoolAuditRecord(
            tenantId = reservation.tenantId,
            campaignId = reservation.campaignId,
            aggregateType = "RESERVATION",
            aggregateId = reservation.reservationId,
            revision = reservation.revision + 1,
            policyVersion = reservation.policyVersion,
            actorType = "WORKER",
            reasonCode = reasonCode,
        )
    }

    private fun terminalizeWorkerAllocation(
        connection: Connection,
        chain: LockedWorkerChain,
        targetState: EntryState,
        reasonCode: String,
    ): VoucherPoolAuditRecord {
        val allocationId = checkNotNull(chain.entry.allocationId)
        val userLimit = checkNotNull(chain.userLimits.singleOrNull())
        val allocation = checkNotNull(lockAllocationById(connection, chain.entry.tenantId, allocationId))

        connection.prepareStatement(
            "UPDATE voucher_pool_allocations SET revision=revision+1 WHERE tenant_id=? AND allocation_id=? AND revision=?",
        ).use { statement ->
            statement.setString(1, allocation.tenantId)
            statement.setObject(2, allocation.allocationId)
            statement.setLong(3, allocation.revision)
            check(statement.executeUpdate() == 1)
        }

        updateUserLimit(connection, userLimit, 0, -1, 0)

        return VoucherPoolAuditRecord(
            tenantId = allocation.tenantId,
            campaignId = allocation.campaignId,
            aggregateType = "ALLOCATION",
            aggregateId = allocation.allocationId,
            revision = allocation.revision + 1,
            policyVersion = allocation.policyVersion,
            actorType = "WORKER",
            reasonCode = reasonCode,
        )
    }

    private fun updateReservationState(connection: Connection, reservation: ReservationRecord, state: String) {
        connection.prepareStatement(
            """UPDATE voucher_pool_reservations SET state=?,revision=revision+1
                WHERE tenant_id=? AND reservation_id=? AND revision=?""".trimIndent(),
        ).use { statement ->
            statement.setString(1, state)
            statement.setString(2, reservation.tenantId)
            statement.setObject(3, reservation.reservationId)
            statement.setLong(4, reservation.revision)

            check(statement.executeUpdate() == 1) { "reservation transition lost its revision" }
        }
    }

    private fun updateUserLimit(
        connection: Connection,
        limit: UserLimitRecord,
        reservationsDelta: Int,
        allocationsDelta: Int,
        lifetimeDelta: Int,
    ) {
        connection.prepareStatement(
            """UPDATE voucher_pool_user_limits SET active_reservations=active_reservations+?,
                  active_allocations=active_allocations+?,lifetime_consumed=lifetime_consumed+?,revision=revision+1
                WHERE tenant_id=? AND campaign_id=? AND user_digest=? AND revision=?""".trimIndent(),
        ).use { statement ->
            statement.setInt(1, reservationsDelta)
            statement.setInt(2, allocationsDelta)
            statement.setInt(3, lifetimeDelta)
            statement.setString(4, limit.tenantId)
            statement.setObject(5, limit.campaignId)
            statement.setBytes(6, limit.userDigest.copyBytes())
            statement.setLong(7, limit.revision)

            check(statement.executeUpdate() == 1) { "user limit transition lost its revision" }
        }
    }

    private fun ResultRow.campaignRecord(): CampaignRecord =
        CampaignRecord(
            tenantId = this[VoucherPoolCampaignTable.tenantId],
            campaignId = this[VoucherPoolCampaignTable.campaignId],
            state = this[VoucherPoolCampaignTable.state],
            policyVersion = this[VoucherPoolCampaignTable.policyVersion],
            startsAt = this[VoucherPoolCampaignTable.startsAt],
            endsAt = this[VoucherPoolCampaignTable.endsAt],
            perUserLimit = this[VoucherPoolCampaignTable.perUserLimit],
            reservationTtlSeconds = this[VoucherPoolCampaignTable.reservationTtlSeconds],
            allocationTtlSeconds = this[VoucherPoolCampaignTable.allocationTtlSeconds],
            replacementAllowance = this[VoucherPoolCampaignTable.replacementAllowance],
            userIdentityKeyVersion = this[VoucherPoolCampaignTable.userIdentityKeyVersion],
            revision = this[VoucherPoolCampaignTable.revision],
            createdAt = this[VoucherPoolCampaignTable.createdAt],
            updatedAt = this[VoucherPoolCampaignTable.updatedAt],
        )

    private fun ResultRow.batchRecord(): BatchRecord =
        BatchRecord(
            tenantId = this[VoucherPoolBatchTable.tenantId],
            batchId = this[VoucherPoolBatchTable.batchId],
            campaignId = this[VoucherPoolBatchTable.campaignId],
            state = this[VoucherPoolBatchTable.state],
            sourceKind = this[VoucherPoolBatchTable.sourceKind],
            provenanceDigest = DigestValue.of(this[VoucherPoolBatchTable.provenanceDigest]),
            requestFingerprint = DigestValue.of(this[VoucherPoolBatchTable.requestFingerprint]),
            policyVersion = this[VoucherPoolBatchTable.policyVersion],
            activatesAt = this[VoucherPoolBatchTable.activatesAt],
            expiresAt = this[VoucherPoolBatchTable.expiresAt],
            nextSourceOrdinal = this[VoucherPoolBatchTable.nextSourceOrdinal],
            expectedCount = this[VoucherPoolBatchTable.expectedCount],
            acceptedCount = this[VoucherPoolBatchTable.acceptedCount],
            rejectedCount = this[VoucherPoolBatchTable.rejectedCount],
            checkpointDigest = this[VoucherPoolBatchTable.checkpointDigest]?.let(DigestValue::of),
            lastFailureCode = this[VoucherPoolBatchTable.lastFailureCode],
            revision = this[VoucherPoolBatchTable.revision],
            createdAt = this[VoucherPoolBatchTable.createdAt],
            updatedAt = this[VoucherPoolBatchTable.updatedAt],
        )

    private fun ResultRow.userLimitRecord(): UserLimitRecord =
        UserLimitRecord(
            tenantId = this[VoucherPoolUserLimitTable.tenantId],
            campaignId = this[VoucherPoolUserLimitTable.campaignId],
            userDigest = DigestValue.of(this[VoucherPoolUserLimitTable.userDigest]),
            activeReservations = this[VoucherPoolUserLimitTable.activeReservations],
            activeAllocations = this[VoucherPoolUserLimitTable.activeAllocations],
            lifetimeConsumed = this[VoucherPoolUserLimitTable.lifetimeConsumed],
            revision = this[VoucherPoolUserLimitTable.revision],
        )

    private fun ResultSet.campaignRecord(): CampaignRecord =
        CampaignRecord(
            tenantId = getString("tenant_id"),
            campaignId = getObject("campaign_id", UUID::class.java),
            state = CampaignState.valueOf(getString("state")),
            policyVersion = getLong("policy_version"),
            startsAt = getTimestamp("starts_at").toInstant(),
            endsAt = getTimestamp("ends_at").toInstant(),
            perUserLimit = getInt("per_user_limit"),
            reservationTtlSeconds = getLong("reservation_ttl_seconds"),
            allocationTtlSeconds = getLong("allocation_ttl_seconds"),
            replacementAllowance = getInt("replacement_allowance"),
            userIdentityKeyVersion = getInt("user_identity_key_version"),
            revision = getLong("revision"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun ResultSet.batchRecord(): BatchRecord =
        BatchRecord(
            tenantId = getString("tenant_id"),
            batchId = getObject("batch_id", UUID::class.java),
            campaignId = getObject("campaign_id", UUID::class.java),
            state = BatchState.valueOf(getString("state")),
            sourceKind = getString("source_kind"),
            provenanceDigest = DigestValue.of(getBytes("provenance_digest")),
            requestFingerprint = DigestValue.of(getBytes("request_fingerprint")),
            policyVersion = getLong("policy_version"),
            activatesAt = getTimestamp("activates_at").toInstant(),
            expiresAt = getTimestamp("expires_at")?.toInstant(),
            nextSourceOrdinal = getLong("next_source_ordinal"),
            expectedCount = getLong("expected_count"),
            acceptedCount = getLong("accepted_count"),
            rejectedCount = getLong("rejected_count"),
            checkpointDigest = getBytes("checkpoint_digest")?.let(DigestValue::of),
            lastFailureCode = getString("last_failure_code"),
            revision = getLong("revision"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun ResultSet.entryRecord(): EntryRecord =
        EntryRecord(
            tenantId = getString("tenant_id"),
            entryId = getObject("entry_id", UUID::class.java),
            campaignId = getObject("campaign_id", UUID::class.java),
            batchId = getObject("batch_id", UUID::class.java),
            sourceOrdinal = getLong("source_ordinal"),
            state = EntryState.valueOf(getString("state")),
            stableDedupDigest = DigestValue.of(getBytes("stable_dedup_digest")),
            verificationDigest = getBytes("verification_digest")?.let(DigestValue::of),
            verificationKeyVersion = getInt("verification_key_version").takeUnless { wasNull() },
            codeCiphertext = getBytes("code_ciphertext")?.let(DigestValue::of),
            codeNonce = getBytes("code_nonce")?.let(DigestValue::of),
            wrappedDek = getBytes("wrapped_dek")?.let(DigestValue::of),
            wrapNonce = getBytes("wrap_nonce")?.let(DigestValue::of),
            kekVersion = getString("kek_version"),
            reservationId = getObject("reservation_id", UUID::class.java),
            allocationId = getObject("allocation_id", UUID::class.java),
            userDigest = getBytes("user_digest")?.let(DigestValue::of),
            reservedAt = getTimestamp("reserved_at")?.toInstant(),
            reservationExpiresAt = getTimestamp("reservation_expires_at")?.toInstant(),
            allocatedAt = getTimestamp("allocated_at")?.toInstant(),
            allocationExpiresAt = getTimestamp("allocation_expires_at")?.toInstant(),
            revealedAt = getTimestamp("revealed_at")?.toInstant(),
            redeemedAt = getTimestamp("redeemed_at")?.toInstant(),
            allocationPolicyVersion = getLong("allocation_policy_version").takeUnless { wasNull() },
            terminalReason = getString("terminal_reason"),
            entitlementRootId = getObject("entitlement_root_id", UUID::class.java),
            replacementCount = getInt("replacement_count"),
            quarantinedAt = getTimestamp("quarantined_at")?.toInstant(),
            revision = getLong("revision"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun ResultSet.lockedVoucherCryptoRecord(): LockedVoucherCryptoRecord =
        LockedVoucherCryptoRecord(
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

    private fun ResultSet.reservationRecord(): ReservationRecord =
        ReservationRecord(
            tenantId = getString("tenant_id"),
            reservationId = getObject("reservation_id", UUID::class.java),
            campaignId = getObject("campaign_id", UUID::class.java),
            batchId = getObject("batch_id", UUID::class.java),
            entryId = getObject("entry_id", UUID::class.java),
            userDigest = DigestValue.of(getBytes("user_digest")),
            idempotencyOwnerDigest = DigestValue.of(getBytes("idempotency_owner_digest")),
            state = getString("state"),
            expiresAt = getTimestamp("reservation_expires_at").toInstant(),
            policyVersion = getLong("policy_version"),
            entitlementRootId = getObject("entitlement_root_id", UUID::class.java),
            replacementOrdinal = getInt("replacement_ordinal"),
            revision = getLong("revision"),
        )

    private fun ResultSet.allocationRecord(): AllocationRecord =
        AllocationRecord(
            tenantId = getString("tenant_id"),
            allocationId = getObject("allocation_id", UUID::class.java),
            reservationId = getObject("reservation_id", UUID::class.java),
            campaignId = getObject("campaign_id", UUID::class.java),
            batchId = getObject("batch_id", UUID::class.java),
            entryId = getObject("entry_id", UUID::class.java),
            userDigest = DigestValue.of(getBytes("user_digest")),
            entitlementRootId = getObject("entitlement_root_id", UUID::class.java),
            replacementOrdinal = getInt("replacement_ordinal"),
            expiresAt = getTimestamp("allocation_expires_at").toInstant(),
            policyVersion = getLong("policy_version"),
            revision = getLong("revision"),
        )
}
