@file:Suppress("LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions", "UnusedParameter")

package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal interface VoucherPoolRepository {
    fun lockCampaignForShare(connection: Connection, tenantId: String, campaignId: UUID): CampaignRecord
    fun lockBatchForShare(connection: Connection, tenantId: String, batchId: UUID): BatchRecord
    fun lockUserLimit(connection: Connection, tenantId: String, campaignId: UUID, userDigest: ByteArray): UserLimitRecord
    fun selectAvailableEntrySkipLocked(connection: Connection, tenantId: String, campaignId: UUID): EntryRecord?
    fun lockCanonicalChain(connection: Connection, candidate: WorkerCandidate): LockedWorkerChain?
    fun appendAudit(connection: Connection, event: VoucherPoolAuditRecord)
}

/** PostgreSQL authority repository; raw JDBC is limited to explicit row-lock syntax. */
internal class JdbcVoucherPoolRepository(private val dataSource: DataSource) : VoucherPoolRepository {
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
        connection.prepareStatement(
            """INSERT INTO voucher_pool_user_limits(tenant_id,campaign_id,user_digest)
                VALUES (?,?,?) ON CONFLICT DO NOTHING""",
        ).use { statement ->
            statement.setString(1, tenantId); statement.setObject(2, campaignId); statement.setBytes(3, userDigest)
            statement.executeUpdate()
        }
        return connection.prepareStatement(
            "SELECT * FROM voucher_pool_user_limits WHERE tenant_id=? AND campaign_id=? AND user_digest=? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tenantId); statement.setObject(2, campaignId); statement.setBytes(3, userDigest)
            statement.executeQuery().use { result ->
                check(result.next())
                UserLimitRecord(
                    tenantId, campaignId, result.getBytes("user_digest"),
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
    ): EntryRecord? = connection.prepareStatement(
        """SELECT e.* FROM voucher_pool_entries e JOIN voucher_pool_batches b
            ON b.tenant_id=e.tenant_id AND b.batch_id=e.batch_id
            WHERE e.tenant_id=? AND e.campaign_id=? AND e.state='AVAILABLE' AND e.quarantined_at IS NULL
              AND b.state='ACTIVE' AND b.activates_at <= transaction_timestamp()
            ORDER BY b.activates_at,e.batch_id,e.source_ordinal,e.entry_id LIMIT 1 FOR UPDATE OF e SKIP LOCKED""",
    ).use { statement ->
        statement.setString(1, tenantId); statement.setObject(2, campaignId)
        statement.executeQuery().use { result -> if (result.next()) result.entryRecord() else null }
    }

    override fun lockCanonicalChain(connection: Connection, candidate: WorkerCandidate): LockedWorkerChain? {
        val campaign = lockCampaignForShare(connection, candidate.tenantId, candidate.campaignId)
        val batch = lockBatchForShare(connection, candidate.tenantId, candidate.batchId)
        val entry = lockEntry(connection, candidate.tenantId, candidate.entryId)
        return LockedWorkerChain(campaign, batch, entry)
    }

    override fun appendAudit(connection: Connection, event: VoucherPoolAuditRecord) {
        connection.prepareStatement(
            """INSERT INTO voucher_pool_audits
                (tenant_id,campaign_id,aggregate_type,aggregate_id,revision,policy_version,actor_type,reason_code)
                VALUES (?,?,?,?,?,?,?,?)""",
        ).use { statement ->
            statement.setString(1, event.tenantId); statement.setObject(2, event.campaignId)
            statement.setString(3, event.aggregateType); statement.setObject(4, event.aggregateId)
            statement.setLong(5, event.revision); statement.setLong(6, event.policyVersion)
            statement.setString(7, event.actorType); statement.setString(8, event.reasonCode)
            statement.executeUpdate()
        }
    }

    internal fun createCampaign(tenantId: String): UUID = UUID.randomUUID().also { id ->
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO voucher_pool_campaigns(tenant_id,campaign_id,state,policy_version,revision) VALUES (?,?,'ACTIVE',1,0)",
            ).use { it.setString(1, tenantId); it.setObject(2, id); it.executeUpdate() }
        }
    }

    internal fun createBatch(tenantId: String, campaignId: UUID): UUID = UUID.randomUUID().also { id ->
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO voucher_pool_batches(tenant_id,batch_id,campaign_id,state,activates_at,revision) VALUES (?,?,?,'ACTIVE',transaction_timestamp(),0)",
            ).use { it.setString(1, tenantId); it.setObject(2, id); it.setObject(3, campaignId); it.executeUpdate() }
        }
    }

    internal fun createAvailableEntry(tenantId: String, campaignId: UUID, batchId: UUID, ordinal: Long): UUID =
        UUID.randomUUID().also { id ->
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO voucher_pool_entries
                        (tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,code_ciphertext,wrapped_dek,nonce,revision)
                        VALUES (?,?,?,?,?,'AVAILABLE',decode('01','hex'),decode('02','hex'),gen_random_bytes(12),0)""",
                ).use {
                    it.setString(1, tenantId); it.setObject(2, id); it.setObject(3, campaignId)
                    it.setObject(4, batchId); it.setLong(5, ordinal); it.executeUpdate()
                }
            }
        }

    internal fun insertDedup(
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        entryId: UUID,
        digest: ByteArray,
        keyVersion: Int,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO voucher_pool_code_dedup(tenant_id,stable_dedup_digest,first_campaign_id,first_batch_id,first_entry_id,key_version) VALUES (?,?,?,?,?,?)",
            ).use {
                it.setString(1, tenantId); it.setBytes(2, digest); it.setObject(3, campaignId)
                it.setObject(4, batchId); it.setObject(5, entryId); it.setInt(6, keyVersion); it.executeUpdate()
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

    internal fun exclusiveWaiters(tenantId: String, campaignId: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT count(*) FROM pg_locks l WHERE NOT l.granted AND l.mode IN ('RowExclusiveLock','ExclusiveLock')
                AND l.relation='voucher_pool_campaigns'::regclass""",
        ).use { statement ->
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    internal fun sharedLockHolders(): Int = dataSource.connection.use { connection ->
        connection.createStatement().executeQuery(
            """SELECT count(DISTINCT pid) FROM pg_locks
                WHERE granted AND mode='RowShareLock' AND relation='voucher_pool_campaigns'::regclass""",
        ).use { result -> result.next(); result.getInt(1) }
    }

    internal fun updateCampaignPolicy(tenantId: String, campaignId: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "UPDATE voucher_pool_campaigns SET policy_version=policy_version+1,revision=revision+1 WHERE tenant_id=? AND campaign_id=?",
        ).use { statement ->
            statement.setString(1, tenantId); statement.setObject(2, campaignId); statement.executeUpdate()
        }
    }

    private fun ResultSet.campaignRecord() = CampaignRecord(
        getString("tenant_id"), getObject("campaign_id", UUID::class.java), CampaignState.valueOf(getString("state")),
        getLong("policy_version"), getLong("revision"),
    )

    private fun ResultSet.batchRecord() = BatchRecord(
        getString("tenant_id"), getObject("batch_id", UUID::class.java), getObject("campaign_id", UUID::class.java),
        BatchState.valueOf(getString("state")), getObject("activates_at", Instant::class.java), getLong("revision"),
    )

    private fun ResultSet.entryRecord() = EntryRecord(
        getString("tenant_id"), getObject("entry_id", UUID::class.java), getObject("campaign_id", UUID::class.java),
        getObject("batch_id", UUID::class.java), getLong("source_ordinal"), EntryState.valueOf(getString("state")),
        getLong("revision"),
    )
}
