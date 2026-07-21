@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import java.io.Serializable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

internal data class BackupKeyManifest(
    val kekVersions: Set<String>,
    val verificationVersions: Set<String>,
    val stableDedupVersion: String,
    val commandTombstoneVersion: String,
    val userIdentityVersions: Set<String> = emptySet(),
    val auditVersions: Set<String> = emptySet(),
    val signatureVersions: Set<String> = emptySet(),
) : Serializable {
    init {
        require(kekVersions.isNotEmpty() && kekVersions.none(String::isBlank)) { "KEK versions must not be empty" }
        require(verificationVersions.isNotEmpty() && verificationVersions.none(String::isBlank)) {
            "verification versions must not be empty"
        }
        require(userIdentityVersions.none(String::isBlank)) { "user identity versions must not be blank" }
        require(auditVersions.none(String::isBlank)) { "audit versions must not be blank" }
        require(signatureVersions.none(String::isBlank)) { "signature versions must not be blank" }
        require(stableDedupVersion.isNotBlank()) { "stable dedup version must not be blank" }
        require(commandTombstoneVersion.isNotBlank()) { "command tombstone version must not be blank" }
    }

    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class VoucherPoolAvailableKeys(
    val kekVersions: Set<String>,
    val verificationVersions: Set<String>,
    val stableDedupVersions: Set<String>,
    val commandTombstoneVersions: Set<String>,
    val userIdentityVersions: Set<String> = emptySet(),
    val auditVersions: Set<String> = emptySet(),
    val signatureVersions: Set<String> = emptySet(),
) : Serializable {
    fun missing(manifest: BackupKeyManifest): Set<String> = buildSet {
        addAll(manifest.kekVersions - kekVersions)
        addAll(manifest.verificationVersions - verificationVersions)
        addAll(manifest.userIdentityVersions - userIdentityVersions)
        addAll(manifest.auditVersions - auditVersions)
        addAll(manifest.signatureVersions - signatureVersions)
        if (manifest.stableDedupVersion !in stableDedupVersions) add(manifest.stableDedupVersion)
        if (manifest.commandTombstoneVersion !in commandTombstoneVersions) add(manifest.commandTombstoneVersion)
    }

    companion object { private const val serialVersionUID: Long = 1L }
}

/** Key references extracted from backup metadata and content independently from the supplied manifest. */
internal data class VoucherPoolBackupKeyInventory(
    val kekVersions: Set<String>,
    val verificationVersions: Set<String>,
    val stableDedupVersions: Set<String>,
    val commandTombstoneVersions: Set<String>,
    val userIdentityVersions: Set<String>,
    val auditVersions: Set<String>,
    val signatureVersions: Set<String>,
) : Serializable {
    init {
        require(
            listOf(
                kekVersions,
                verificationVersions,
                stableDedupVersions,
                commandTombstoneVersions,
                userIdentityVersions,
                auditVersions,
                signatureVersions,
            ).all { versions -> versions.isNotEmpty() && versions.none(String::isBlank) },
        ) { "backup key inventory categories must be complete" }
    }

    fun missingFrom(manifest: BackupKeyManifest): Set<String> = buildSet {
        addAll(kekVersions - manifest.kekVersions)
        addAll(verificationVersions - manifest.verificationVersions)
        addAll(userIdentityVersions - manifest.userIdentityVersions)
        addAll(auditVersions - manifest.auditVersions)
        addAll(signatureVersions - manifest.signatureVersions)
        addAll(stableDedupVersions - setOf(manifest.stableDedupVersion))
        addAll(commandTombstoneVersions - setOf(manifest.commandTombstoneVersion))
    }

    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class VoucherPoolRestoreSmokeResult(
    val ciphertextReadableOrQuarantined: Boolean,
    val countersConsistent: Boolean,
    val replayFenceRetained: Boolean,
    val cursorRestarted: Boolean,
    val staleWorkerTakenOver: Boolean,
    val exactlyOnceRevealRetained: Boolean,
) : Serializable {
    val passed: Boolean
        get() = ciphertextReadableOrQuarantined && countersConsistent && replayFenceRetained && cursorRestarted &&
            staleWorkerTakenOver && exactlyOnceRevealRetained

    companion object { private const val serialVersionUID: Long = 1L }
}

internal enum class VoucherPoolRestoreFailureCode {
    INCOMPLETE_MANIFEST,
    MISSING_KEY,
    SMOKE_FAILED,
}

internal class VoucherPoolRestoreException(val code: VoucherPoolRestoreFailureCode) :
    IllegalStateException("VOUCHER_POOL_RESTORE_${code.name}")

/** Validates every manifest key before import and accepts a restore only after the complete smoke contract passes. */
internal class VoucherPoolRestoreCoordinator(
    private val availableKeys: VoucherPoolAvailableKeys,
) {
    fun restore(
        manifest: BackupKeyManifest,
        inventory: VoucherPoolBackupKeyInventory,
        importer: () -> Unit,
        smoke: () -> VoucherPoolRestoreSmokeResult,
    ): VoucherPoolRestoreSmokeResult {
        requirePreflight(manifest, inventory)
        importer()
        val result = smoke()
        if (!result.passed) throw VoucherPoolRestoreException(VoucherPoolRestoreFailureCode.SMOKE_FAILED)
        return result
    }

    private fun requirePreflight(manifest: BackupKeyManifest, inventory: VoucherPoolBackupKeyInventory) {
        if (inventory.missingFrom(manifest).isNotEmpty()) {
            throw VoucherPoolRestoreException(VoucherPoolRestoreFailureCode.INCOMPLETE_MANIFEST)
        }
        if (availableKeys.missing(manifest).isNotEmpty()) {
            throw VoucherPoolRestoreException(VoucherPoolRestoreFailureCode.MISSING_KEY)
        }
    }
}

internal data class VoucherPoolReferencedKeys(
    val liveRows: Set<String>,
    val commandTombstones: Set<String>,
    val stableDedup: Set<String>,
    val audits: Set<String>,
    val backupInventory: Set<String>,
    val restoreRehearsalPassed: Boolean,
) : Serializable {
    val all: Set<String> get() = liveRows + commandTombstones + stableDedup + audits + backupInventory

    companion object { private const val serialVersionUID: Long = 1L }
}

internal fun interface VoucherPoolKeyReferenceSource {
    fun referencedKeys(): VoucherPoolReferencedKeys
}

/** Reads all persisted key references, including retained or unrehearsed backup inventory. */
internal class PostgresVoucherPoolKeyReferenceSource(private val dataSource: DataSource) : VoucherPoolKeyReferenceSource {
    override fun referencedKeys(): VoucherPoolReferencedKeys = dataSource.connection.use { connection ->
        val backup = readBackupInventory(connection)
        VoucherPoolReferencedKeys(
            liveRows = queryStrings(connection, LIVE_KEY_SQL),
            commandTombstones = queryStrings(connection, TOMBSTONE_KEY_SQL),
            stableDedup = queryStrings(connection, DEDUP_KEY_SQL),
            audits = queryStrings(connection, AUDIT_KEY_SQL),
            backupInventory = backup.keys,
            restoreRehearsalPassed = backup.allRehearsed,
        )
    }

    private fun readBackupInventory(connection: Connection): BackupReferences {
        val result = connection.createStatement().use { statement ->
            statement.executeQuery(BACKUP_INVENTORY_SQL).use(::collectBackupReferences)
        }
        return BackupReferences(result.keys, result.count > 0L && result.count == result.rehearsedCount)
    }

    private fun collectBackupReferences(result: ResultSet): BackupReferenceResult {
        val backup = mutableSetOf<String>()
        var backupCount = 0L
        var rehearsedCount = 0L
        while (result.next()) {
            backupCount++
            if (result.getBoolean("rehearsed")) rehearsedCount++
            if (result.getBoolean("blocking")) {
                backup += result.getArray("kek_versions").array.asStringSet()
                backup += result.getArray("verification_versions").array.asStringSet()
                backup += result.getArray("user_identity_versions").array.asStringSet()
                backup += result.getArray("audit_versions").array.asStringSet()
                backup += result.getArray("signature_versions").array.asStringSet()
                backup += result.getString("stable_dedup_version")
                backup += result.getString("command_tombstone_version")
            }
        }
        return BackupReferenceResult(backup, backupCount, rehearsedCount)
    }

    private fun queryStrings(connection: Connection, sql: String): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result -> buildSet { while (result.next()) add(result.getString(1)) } }
        }

    private fun Any.asStringSet(): Set<String> =
        when (this) {
            is Array<*> -> filterIsInstance<String>().toSet()
            else -> emptySet()
        }

    private companion object {
        class BackupReferences(val keys: Set<String>, val allRehearsed: Boolean)
        class BackupReferenceResult(val keys: Set<String>, val count: Long, val rehearsedCount: Long)

        val BACKUP_INVENTORY_SQL =
            """SELECT kek_versions,verification_versions,user_identity_versions,audit_versions,signature_versions,
                       stable_dedup_version,command_tombstone_version,
                       restore_rehearsed_at IS NOT NULL AS rehearsed,
                       retained_until>statement_timestamp() OR restore_rehearsed_at IS NULL AS blocking
                FROM voucher_pool_backup_inventory""".trimIndent()

        val LIVE_KEY_SQL =
            """SELECT kek_version FROM voucher_pool_entries WHERE kek_version IS NOT NULL
                UNION SELECT verification_key_version::text FROM voucher_pool_entries WHERE verification_key_version IS NOT NULL
                UNION SELECT user_identity_key_version::text FROM voucher_pool_campaigns
                UNION SELECT signature_key_version::text FROM voucher_pool_revoke_preview_grants""".trimIndent()
        const val TOMBSTONE_KEY_SQL = "SELECT key_version::text FROM voucher_pool_command_tombstones"
        const val DEDUP_KEY_SQL = "SELECT key_version::text FROM voucher_pool_code_dedup"
        const val AUDIT_KEY_SQL = "SELECT audit_key_version::text FROM voucher_pool_audits WHERE audit_key_version IS NOT NULL"
    }
}

internal class VoucherPoolKeyRetirementPolicy(private val references: VoucherPoolKeyReferenceSource) {
    fun canRetire(version: String): Boolean {
        require(version.isNotBlank()) { "version must not be blank" }
        val current = references.referencedKeys()
        return current.restoreRehearsalPassed && version !in current.all
    }
}

internal enum class VoucherPoolRollbackStrategy {
    COMPATIBLE_PREVIOUS_BINARY,
    VERIFIED_RESTORE_AND_ROLL_FORWARD,
}

internal object VoucherPoolRollbackPolicy {
    fun select(
        previousBinaryCompatible: Boolean,
        verifiedBackup: Boolean,
        restoreRehearsalPassed: Boolean,
    ): VoucherPoolRollbackStrategy =
        when {
            previousBinaryCompatible -> VoucherPoolRollbackStrategy.COMPATIBLE_PREVIOUS_BINARY
            verifiedBackup && restoreRehearsalPassed -> VoucherPoolRollbackStrategy.VERIFIED_RESTORE_AND_ROLL_FORWARD
            else -> error("rollback requires a compatible previous binary or verified backup manifest")
        }
}

internal enum class RetentionKind {
    DESCRIPTOR,
    TERMINAL_INBOX_CLAIM,
    TERMINAL_ENTRY_RESERVATION,
    AUDIT,
}

internal enum class RetentionStage {
    DESCRIPTORS,
    TERMINAL_INBOX_AND_CLAIMS,
    TERMINAL_RESERVATIONS_AND_ENTRIES,
    AUDIT,
}

internal data class VoucherPoolRetentionPolicy(
    val descriptor: Duration = Duration.ofHours(24),
    val terminalInboxAndClaim: Duration = Duration.ofDays(7),
    val terminalEntryAndReservation: Duration = Duration.ofDays(30),
    val audit: Duration = Duration.ofDays(90),
) : Serializable {
    init {
        requirePositive(descriptor, "descriptor")
        requirePositive(terminalInboxAndClaim, "terminalInboxAndClaim")
        requirePositive(terminalEntryAndReservation, "terminalEntryAndReservation")
        requirePositive(audit, "audit")
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        private fun requirePositive(value: Duration, name: String) {
            require(!value.isNegative && !value.isZero) { "$name retention must be positive" }
        }
    }
}

internal data class VoucherPoolRetentionReport(
    private val deletedByStage: Map<RetentionStage, Int>,
) : Serializable {
    val stages: List<RetentionStage> get() = RetentionStage.entries

    fun count(stage: RetentionStage): Int = deletedByStage[stage] ?: 0

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class VoucherPoolRetentionBacklogMetric(
    val count: Long,
    val oldestAge: Duration,
) : Serializable {
    init {
        require(count >= 0L) { "retention backlog count must not be negative" }
        require(!oldestAge.isNegative) { "retention backlog age must not be negative" }
    }

    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class VoucherPoolRetentionBacklog(
    private val metrics: Map<RetentionKind, VoucherPoolRetentionBacklogMetric>,
) : Serializable {
    operator fun get(kind: RetentionKind): VoucherPoolRetentionBacklogMetric =
        metrics.getValue(kind)

    companion object { private const val serialVersionUID: Long = 1L }
}

/** PostgreSQL-time, dependency-ordered retention for voucher pool authority rows. */
internal class VoucherPoolRetention(
    private val dataSource: DataSource,
    private val policy: VoucherPoolRetentionPolicy = VoucherPoolRetentionPolicy(),
) {
    /** Returns bounded-cardinality due-row count and oldest overdue age using PostgreSQL time. */
    fun backlog(): VoucherPoolRetentionBacklog = dataSource.connection.use { connection ->
        VoucherPoolRetentionBacklog(
            linkedMapOf(
                RetentionKind.DESCRIPTOR to
                    queryBacklog(connection, DESCRIPTOR_BACKLOG_SQL, policy.descriptor, policy.descriptor),
                RetentionKind.TERMINAL_INBOX_CLAIM to
                    queryBacklog(
                        connection,
                        INBOX_CLAIM_BACKLOG_SQL,
                        policy.terminalInboxAndClaim,
                        policy.terminalInboxAndClaim,
                    ),
                RetentionKind.TERMINAL_ENTRY_RESERVATION to
                    queryBacklog(
                        connection,
                        ENTRY_RESERVATION_BACKLOG_SQL,
                        policy.terminalEntryAndReservation,
                        policy.terminalEntryAndReservation,
                    ),
                RetentionKind.AUDIT to queryBacklog(connection, AUDIT_BACKLOG_SQL, policy.audit, policy.audit),
            ),
        )
    }

    fun purge(limit: Int): VoucherPoolRetentionReport {
        require(limit in 1..MAX_PURGE_LIMIT) { "purge limit must be in 1..$MAX_PURGE_LIMIT" }
        return transaction { connection ->
            val descriptors = purgeDescriptors(connection, limit)
            val inboxAndClaims = purgeTerminalInbox(connection, limit) + purgeTerminalClaims(connection, limit)
            val reservationsAndEntries =
                purgeTerminalAllocations(connection, limit) +
                    purgeTerminalReservations(connection, limit) +
                    purgeTerminalEntries(connection, limit)
            val audits = purgeAudits(connection, limit)
            VoucherPoolRetentionReport(
                linkedMapOf(
                    RetentionStage.DESCRIPTORS to descriptors,
                    RetentionStage.TERMINAL_INBOX_AND_CLAIMS to inboxAndClaims,
                    RetentionStage.TERMINAL_RESERVATIONS_AND_ENTRIES to reservationsAndEntries,
                    RetentionStage.AUDIT to audits,
                ),
            ).also {
                log.info {
                    "voucher_pool_retention_purged descriptors=$descriptors inboxClaims=$inboxAndClaims " +
                        "terminalRows=$reservationsAndEntries audits=$audits"
                }
            }
        }
    }

    /** Deletes tenant-lifetime fences only after every backup retention and rehearsal hold is released. */
    fun deleteTenant(tenantId: String): Boolean {
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        return transaction { connection ->
            if (isTenantHeld(connection, tenantId) || !isTenantBackupDeletionReady(connection, tenantId)) {
                log.warn { "voucher_pool_tenant_delete_held" }
                return@transaction false
            }
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL voucher_pool.retention_purge = 'on'")
                statement.execute("SET CONSTRAINTS ALL DEFERRED")
            }
            TENANT_DELETE_ORDER.forEach { table ->
                connection.prepareStatement("DELETE FROM $table WHERE tenant_id=?").use { statement ->
                    statement.setString(1, tenantId)
                    statement.executeUpdate()
                }
            }
            log.info { "voucher_pool_tenant_deleted" }
            true
        }
    }

    private fun purgeDescriptors(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT i.ctid FROM voucher_pool_http_idempotency i
                    JOIN voucher_pool_command_tombstones t
                      ON t.tenant_id=i.tenant_id AND t.operation=i.operation
                     AND t.scoped_key_digest=i.scoped_key_digest AND t.fingerprint=i.fingerprint
                    WHERE i.status='COMPLETED' AND i.descriptor IS NOT NULL
                      AND i.completed_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT ${heldTenantSql("i.tenant_id")}
                    ORDER BY i.completed_at,i.tenant_id,i.operation
                    LIMIT ? FOR UPDATE OF i SKIP LOCKED)
                UPDATE voucher_pool_http_idempotency i SET descriptor=NULL,revision=revision+1
                FROM candidates WHERE i.ctid=candidates.ctid""",
        ).executeRetention(policy.descriptor, limit)

    private fun purgeTerminalInbox(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT i.ctid FROM voucher_pool_reconciliation_inbox i
                    WHERE i.status IN ('APPLIED','IGNORED','FAILED_TERMINAL')
                      AND i.claim_owner IS NULL
                      AND i.next_attempt_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT ${heldTenantSql("i.tenant_id")}
                    ORDER BY i.next_attempt_at,i.tenant_id,i.event_id
                    LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM voucher_pool_reconciliation_inbox i USING candidates WHERE i.ctid=candidates.ctid""",
        ).executeRetention(policy.terminalInboxAndClaim, limit)

    private fun purgeTerminalClaims(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT w.ctid FROM voucher_pool_worker_claims w
                    WHERE w.owner_id IS NULL AND w.checkpoint>0
                      AND w.next_attempt_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT ${heldTenantSql("w.tenant_id")}
                    ORDER BY w.next_attempt_at,w.tenant_id,w.scope_id
                    LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM voucher_pool_worker_claims w USING candidates WHERE w.ctid=candidates.ctid""",
        ).executeRetention(policy.terminalInboxAndClaim, limit)

    private fun purgeTerminalAllocations(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT a.ctid FROM voucher_pool_allocations a
                    JOIN voucher_pool_entries e ON e.tenant_id=a.tenant_id AND e.entry_id=a.entry_id
                    WHERE e.state IN ('REDEEMED','RELEASED','REVOKED','EXPIRED')
                      AND e.updated_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT ${heldTenantSql("e.tenant_id")}
                    ORDER BY e.updated_at,a.tenant_id,a.allocation_id
                    LIMIT ? FOR UPDATE OF a SKIP LOCKED)
                DELETE FROM voucher_pool_allocations a USING candidates WHERE a.ctid=candidates.ctid""",
        ).executeRetention(policy.terminalEntryAndReservation, limit)

    private fun purgeTerminalReservations(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT r.ctid FROM voucher_pool_reservations r
                    WHERE r.state IN ('EXPIRED','RELEASED','REVOKED')
                      AND r.reservation_expires_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT EXISTS (SELECT 1 FROM voucher_pool_allocations a
                                      WHERE a.tenant_id=r.tenant_id AND a.reservation_id=r.reservation_id)
                      AND NOT ${heldTenantSql("r.tenant_id")}
                    ORDER BY r.reservation_expires_at,r.tenant_id,r.reservation_id
                    LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM voucher_pool_reservations r USING candidates WHERE r.ctid=candidates.ctid""",
        ).executeRetention(policy.terminalEntryAndReservation, limit)

    private fun purgeTerminalEntries(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT e.ctid FROM voucher_pool_entries e
                    WHERE e.state IN ('REDEEMED','RELEASED','REVOKED','EXPIRED')
                      AND e.updated_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT EXISTS (SELECT 1 FROM voucher_pool_reservations r
                                      WHERE r.tenant_id=e.tenant_id AND r.entry_id=e.entry_id)
                      AND NOT EXISTS (SELECT 1 FROM voucher_pool_allocations a
                                      WHERE a.tenant_id=e.tenant_id AND a.entry_id=e.entry_id)
                      AND NOT ${heldTenantSql("e.tenant_id")}
                    ORDER BY e.updated_at,e.tenant_id,e.entry_id
                    LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM voucher_pool_entries e USING candidates WHERE e.ctid=candidates.ctid""",
        ).executeRetention(policy.terminalEntryAndReservation, limit)

    private fun purgeAudits(connection: Connection, limit: Int): Int {
        connection.createStatement().use { it.execute("SET LOCAL voucher_pool.retention_purge = 'on'") }
        return connection.prepareStatement(
            """WITH candidates AS (
                    SELECT a.ctid FROM voucher_pool_audits a
                    WHERE a.created_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT ${heldTenantSql("a.tenant_id")}
                    ORDER BY a.created_at,a.id
                    LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM voucher_pool_audits a USING candidates WHERE a.ctid=candidates.ctid""",
        ).executeRetention(policy.audit, limit)
    }

    private fun isTenantHeld(connection: Connection, tenantId: String): Boolean =
        connection.prepareStatement(
            """SELECT EXISTS (
                    SELECT 1 FROM voucher_pool_legal_holds h
                    WHERE h.tenant_id=? AND h.released_at IS NULL
                      AND (h.hold_until IS NULL OR h.hold_until>statement_timestamp())
                    UNION ALL
                    SELECT 1 FROM voucher_pool_quarantines q
                    WHERE q.tenant_id=? AND q.resolved_at IS NULL)""",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, tenantId)
            statement.executeQuery().use { result -> check(result.next()); result.getBoolean(1) }
        }

    private fun isTenantBackupDeletionReady(connection: Connection, tenantId: String): Boolean =
        connection.prepareStatement(
            """SELECT EXISTS (SELECT 1 FROM voucher_pool_backup_inventory WHERE tenant_id=?)
                   AND NOT EXISTS (SELECT 1 FROM voucher_pool_backup_inventory
                       WHERE tenant_id=? AND (retained_until>statement_timestamp() OR restore_rehearsed_at IS NULL))""",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, tenantId)
            statement.executeQuery().use { result -> check(result.next()); result.getBoolean(1) }
        }

    private fun heldTenantSql(alias: String): String =
        """EXISTS (SELECT 1 FROM voucher_pool_legal_holds h
                     WHERE h.tenant_id=$alias AND h.released_at IS NULL
                       AND (h.hold_until IS NULL OR h.hold_until>statement_timestamp())
                   UNION ALL
                   SELECT 1 FROM voucher_pool_quarantines q
                     WHERE q.tenant_id=$alias AND q.resolved_at IS NULL
                   UNION ALL
                   SELECT 1 WHERE NOT EXISTS (
                     SELECT 1 FROM voucher_pool_backup_inventory b
                     WHERE b.tenant_id=$alias)
                   UNION ALL
                   SELECT 1 FROM voucher_pool_backup_inventory b
                     WHERE b.tenant_id=$alias AND b.restore_rehearsed_at IS NULL)"""

    private fun PreparedStatement.executeRetention(duration: Duration, limit: Int): Int = use { statement ->
        statement.setLong(1, duration.toMillis())
        statement.setInt(2, limit)
        statement.executeUpdate()
    }

    private fun queryBacklog(
        connection: Connection,
        sql: String,
        vararg durations: Duration,
    ): VoucherPoolRetentionBacklogMetric = connection.prepareStatement(sql).use { statement ->
        durations.forEachIndexed { index, duration -> statement.setLong(index + 1, duration.toMillis()) }
        statement.executeQuery().use { result ->
            check(result.next())
            VoucherPoolRetentionBacklogMetric(
                count = result.getLong("due_count"),
                oldestAge = Duration.ofMillis(result.getLong("oldest_millis")),
            )
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            runCatching {
                block(connection).also { connection.commit() }
            }.getOrElse { failure ->
                try {
                    connection.rollback()
                } catch (rollbackFailure: SQLException) {
                    failure.addSuppressed(rollbackFailure)
                }
                log.warn(failure) { "voucher_pool_retention_transaction_failed" }
                throw failure
            }
        }

    companion object : KLogging() {
        private const val MAX_PURGE_LIMIT = 1_000
        private val DESCRIPTOR_BACKLOG_SQL =
            """SELECT count(*) AS due_count,
                       COALESCE(max(EXTRACT(EPOCH FROM
                           (statement_timestamp()-(completed_at+(? * interval '1 millisecond'))))*1000),0)::bigint
                           AS oldest_millis
                FROM voucher_pool_http_idempotency
                WHERE status='COMPLETED' AND descriptor IS NOT NULL
                  AND completed_at+(? * interval '1 millisecond')<=statement_timestamp()""".trimIndent()
        private val INBOX_CLAIM_BACKLOG_SQL =
            """SELECT count(*) AS due_count,
                       COALESCE(max(EXTRACT(EPOCH FROM (statement_timestamp()-eligible_at))*1000),0)::bigint AS oldest_millis
                FROM (
                    SELECT next_attempt_at+(? * interval '1 millisecond') AS eligible_at
                    FROM voucher_pool_reconciliation_inbox
                    WHERE status IN ('APPLIED','IGNORED','FAILED_TERMINAL') AND claim_owner IS NULL
                    UNION ALL
                    SELECT next_attempt_at+(? * interval '1 millisecond') AS eligible_at
                    FROM voucher_pool_worker_claims WHERE owner_id IS NULL AND checkpoint>0
                ) due WHERE eligible_at<=statement_timestamp()""".trimIndent()
        private val ENTRY_RESERVATION_BACKLOG_SQL =
            """SELECT count(*) AS due_count,
                       COALESCE(max(EXTRACT(EPOCH FROM (statement_timestamp()-eligible_at))*1000),0)::bigint AS oldest_millis
                FROM (
                    SELECT updated_at+(? * interval '1 millisecond') AS eligible_at
                    FROM voucher_pool_entries WHERE state IN ('REDEEMED','RELEASED','REVOKED','EXPIRED')
                    UNION ALL
                    SELECT reservation_expires_at+(? * interval '1 millisecond') AS eligible_at
                    FROM voucher_pool_reservations WHERE state IN ('EXPIRED','RELEASED','REVOKED')
                ) due WHERE eligible_at<=statement_timestamp()""".trimIndent()
        private val AUDIT_BACKLOG_SQL =
            """SELECT count(*) AS due_count,
                       COALESCE(max(EXTRACT(EPOCH FROM (statement_timestamp()-(created_at+(? * interval '1 millisecond'))))*1000),0)::bigint AS oldest_millis
                FROM voucher_pool_audits
                WHERE created_at+(? * interval '1 millisecond')<=statement_timestamp()""".trimIndent()
        private val TENANT_DELETE_ORDER =
            listOf(
                "voucher_pool_quarantines",
                "voucher_pool_revoke_preview_grants",
                "voucher_pool_reconciliation_inbox",
                "voucher_pool_worker_claims",
                "voucher_pool_pool_depth",
                "voucher_pool_allocations",
                "voucher_pool_reservations",
                "voucher_pool_user_limits",
                "voucher_pool_audits",
                "voucher_pool_entries",
                "voucher_pool_batches",
                "voucher_pool_campaigns",
                "voucher_pool_http_idempotency",
                "voucher_pool_command_tombstones",
                "voucher_pool_code_dedup",
                "voucher_pool_legal_holds",
                "voucher_pool_backup_inventory",
            )
    }
}
