@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.javatimes.days
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.requireZeroOrPositiveDuration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAuditTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAllocationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBackupInventoryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBatchTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCampaignTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCodeDedupTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCommandTombstoneTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolDepthTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolEntryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolHttpIdempotencyTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolLegalHoldTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolQuarantineTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolReconciliationInboxTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolReservationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRevokePreviewGrantTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolUserLimitTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolWorkerClaimTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
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
): Serializable {

    companion object: KLogging() {
        private const val serialVersionUID: Long = 1L
    }

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

}

internal data class VoucherPoolAvailableKeys(
    val kekVersions: Set<String>,
    val verificationVersions: Set<String>,
    val stableDedupVersions: Set<String>,
    val commandTombstoneVersions: Set<String>,
    val userIdentityVersions: Set<String> = emptySet(),
    val auditVersions: Set<String> = emptySet(),
    val signatureVersions: Set<String> = emptySet(),
): Serializable {
    fun missing(manifest: BackupKeyManifest): Set<String> = buildSet {
        addAll(manifest.kekVersions - kekVersions)
        addAll(manifest.verificationVersions - verificationVersions)
        addAll(manifest.userIdentityVersions - userIdentityVersions)
        addAll(manifest.auditVersions - auditVersions)
        addAll(manifest.signatureVersions - signatureVersions)
        if (manifest.stableDedupVersion !in stableDedupVersions) add(manifest.stableDedupVersion)
        if (manifest.commandTombstoneVersion !in commandTombstoneVersions) add(manifest.commandTombstoneVersion)
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 제공된 manifest와 독립적으로 backup metadata와 content에서 추출한 key reference입니다. */
internal data class VoucherPoolBackupKeyInventory(
    val kekVersions: Set<String>,
    val verificationVersions: Set<String>,
    val stableDedupVersions: Set<String>,
    val commandTombstoneVersions: Set<String>,
    val userIdentityVersions: Set<String>,
    val auditVersions: Set<String>,
    val signatureVersions: Set<String>,
): Serializable {
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

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class VoucherPoolRestoreSmokeResult(
    val ciphertextReadableOrQuarantined: Boolean,
    val countersConsistent: Boolean,
    val replayFenceRetained: Boolean,
    val cursorRestarted: Boolean,
    val staleWorkerTakenOver: Boolean,
    val exactlyOnceRevealRetained: Boolean,
): Serializable {
    val passed: Boolean
        get() = ciphertextReadableOrQuarantined && countersConsistent && replayFenceRetained && cursorRestarted &&
                staleWorkerTakenOver && exactlyOnceRevealRetained

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal enum class VoucherPoolRestoreFailureCode {
    INCOMPLETE_MANIFEST,
    MISSING_KEY,
    SMOKE_FAILED,
}

internal class VoucherPoolRestoreException(val code: VoucherPoolRestoreFailureCode):
    IllegalStateException("VOUCHER_POOL_RESTORE_${code.name}")

/** import 전에 모든 manifest key를 검증하고, complete smoke contract가 통과한 뒤에만 restore를 수락합니다. */
internal class VoucherPoolRestoreCoordinator(
    private val availableKeys: VoucherPoolAvailableKeys,
) {

    companion object: KLogging()

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
): Serializable {
    val all: Set<String> get() = liveRows + commandTombstones + stableDedup + audits + backupInventory

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal fun interface VoucherPoolKeyReferenceSource {
    fun referencedKeys(): VoucherPoolReferencedKeys
}

/** retained 또는 unrehearsed backup inventory를 포함해 persisted key reference를 모두 읽습니다. */
@Suppress("SqlResolve")
internal class PostgresVoucherPoolKeyReferenceSource(
    private val dataSource: DataSource,
): VoucherPoolKeyReferenceSource {

    private companion object: KLogging() {
        class BackupReferences(val keys: Set<String>, val allRehearsed: Boolean)
        class BackupReferenceResult(val keys: Set<String>, val count: Long, val rehearsedCount: Long)

        val BACKUP_INVENTORY_SQL =
            """SELECT kek_versions,verification_versions,user_identity_versions,audit_versions,signature_versions,
                       stable_dedup_version,command_tombstone_version,
                       restore_rehearsed_at IS NOT NULL AS rehearsed,
                       retained_until>statement_timestamp() OR restore_rehearsed_at IS NULL AS blocking
                FROM voucher_pool_backup_inventory""".trimIndent()

    }

    override fun referencedKeys(): VoucherPoolReferencedKeys =
        withExposedTransaction { connection ->
            val backup = readBackupInventory(connection)
            VoucherPoolReferencedKeys(
                liveRows = buildSet {
                    addAll(queryStrings(VoucherPoolEntryTable.kekVersion) { VoucherPoolEntryTable.select(VoucherPoolEntryTable.kekVersion).where { VoucherPoolEntryTable.kekVersion.isNotNull() } })
                    addAll(queryNullableInts(VoucherPoolEntryTable.verificationKeyVersion) { VoucherPoolEntryTable.select(VoucherPoolEntryTable.verificationKeyVersion).where { VoucherPoolEntryTable.verificationKeyVersion.isNotNull() } })
                    addAll(queryInts(VoucherPoolCampaignTable.userIdentityKeyVersion) { VoucherPoolCampaignTable.select(VoucherPoolCampaignTable.userIdentityKeyVersion) })
                    addAll(queryInts(VoucherPoolRevokePreviewGrantTable.signatureKeyVersion) { VoucherPoolRevokePreviewGrantTable.select(VoucherPoolRevokePreviewGrantTable.signatureKeyVersion) })
                },
                commandTombstones = queryInts(VoucherPoolCommandTombstoneTable.keyVersion) { VoucherPoolCommandTombstoneTable.select(VoucherPoolCommandTombstoneTable.keyVersion) },
                stableDedup = queryInts(VoucherPoolCodeDedupTable.keyVersion) { VoucherPoolCodeDedupTable.select(VoucherPoolCodeDedupTable.keyVersion) },
                audits = queryNullableInts(VoucherPoolAuditTable.auditKeyVersion) { VoucherPoolAuditTable.select(VoucherPoolAuditTable.auditKeyVersion).where { VoucherPoolAuditTable.auditKeyVersion.isNotNull() } },
                backupInventory = backup.keys,
                restoreRehearsalPassed = backup.allRehearsed,
            )
        }

    /** PostgreSQL array materialization과 statement-clock hold를 함께 읽어야 하므로 raw로 유지합니다. */
    private fun readBackupInventory(connection: Connection): BackupReferences {
        val result = connection.createStatement().use { statement ->
            statement.executeQuery(BACKUP_INVENTORY_SQL).use(::collectBackupReferences)
        }
        return BackupReferences(
            keys = result.keys,
            allRehearsed = result.count > 0L && result.count == result.rehearsedCount
        )
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

    private fun queryStrings(column: Column<String?>, query: () -> Query): Set<String> =
        query().withDistinct().orderBy(column to SortOrder.ASC).mapNotNull { it[column] }.toSet()

    private fun queryInts(column: Column<Int>, query: () -> Query): Set<String> =
        query().withDistinct().orderBy(column to SortOrder.ASC).map { it[column].toString() }.toSet()

    private fun queryNullableInts(column: Column<Int?>, query: () -> Query): Set<String> =
        query().withDistinct().orderBy(column to SortOrder.ASC).mapNotNull { it[column]?.toString() }.toSet()

    private inline fun <T> withExposedTransaction(crossinline block: (Connection) -> T): T {
        val database = Database.connect(dataSource)
        return try {
            transaction(database) {
                block(TransactionManager.current().connection.connection as Connection)
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    private fun Any.asStringSet(): Set<String> = when (this) {
        is Array<*> -> filterIsInstance<String>().toSet()
        else        -> emptySet()
    }
}

internal class VoucherPoolKeyRetirementPolicy(private val references: VoucherPoolKeyReferenceSource) {
    fun canRetire(version: String): Boolean {
        version.requireNotBlank("version")
        val current = references.referencedKeys()

        return current.restoreRehearsalPassed && (version !in current.all)
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
    ): VoucherPoolRollbackStrategy = when {
        previousBinaryCompatible                 -> VoucherPoolRollbackStrategy.COMPATIBLE_PREVIOUS_BINARY
        verifiedBackup && restoreRehearsalPassed -> VoucherPoolRollbackStrategy.VERIFIED_RESTORE_AND_ROLL_FORWARD
        else                                     -> error("rollback requires a compatible previous binary or verified backup manifest")
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
    val descriptor: Duration = 24.hours(),
    val terminalInboxAndClaim: Duration = 7.days(),
    val terminalEntryAndReservation: Duration = 30.days(),
    val audit: Duration = 90.days(),
): Serializable {
    init {
        descriptor.requirePositiveDuration("descriptor")
        terminalInboxAndClaim.requirePositiveDuration("terminalInboxAndClaim")
        terminalEntryAndReservation.requirePositiveDuration("terminalEntryAndReservation")
        audit.requirePositiveDuration("audit")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class VoucherPoolRetentionReport(
    private val deletedByStage: Map<RetentionStage, Int>,
): Serializable {

    val stages: List<RetentionStage> get() = RetentionStage.entries

    fun count(stage: RetentionStage): Int = deletedByStage[stage] ?: 0

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class VoucherPoolRetentionBacklogMetric(
    val count: Long,
    val oldestAge: Duration,
): Serializable {
    init {
        count.requireZeroOrPositiveNumber("count")
        oldestAge.requireZeroOrPositiveDuration("oldestAge")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class VoucherPoolRetentionBacklog(
    private val metrics: Map<RetentionKind, VoucherPoolRetentionBacklogMetric>,
): Serializable {
    operator fun get(kind: RetentionKind): VoucherPoolRetentionBacklogMetric =
        metrics.getValue(kind)

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** voucher pool authority row를 위한 PostgreSQL-time, dependency-ordered retention입니다. */
internal class VoucherPoolRetention(
    private val dataSource: DataSource,
    private val policy: VoucherPoolRetentionPolicy = VoucherPoolRetentionPolicy(),
) {
    /**
     * PostgreSQL `statement_timestamp()`와 interval projection을 한 snapshot에 고정해
     * bounded-cardinality due-row count와 oldest overdue age를 반환합니다.
     * Exposed DSL에는 이 statement-clock/interval projection을 같은 SQL 경계로 표현하는
     * portable API가 없어 backlog read-model은 raw SQL로 유지합니다.
     */
    fun backlog(): VoucherPoolRetentionBacklog =
        dataSource.connection.use { connection ->
            VoucherPoolRetentionBacklog(
                linkedMapOf(
                    RetentionKind.DESCRIPTOR to queryBacklog(
                        connection,
                        DESCRIPTOR_BACKLOG_SQL,
                        policy.descriptor,
                        policy.descriptor
                    ),
                    RetentionKind.TERMINAL_INBOX_CLAIM to queryBacklog(
                        connection,
                        INBOX_CLAIM_BACKLOG_SQL,
                        policy.terminalInboxAndClaim,
                        policy.terminalInboxAndClaim,
                    ),
                    RetentionKind.TERMINAL_ENTRY_RESERVATION to queryBacklog(
                        connection,
                        ENTRY_RESERVATION_BACKLOG_SQL,
                        policy.terminalEntryAndReservation,
                        policy.terminalEntryAndReservation,
                    ),
                    RetentionKind.AUDIT to queryBacklog(
                        connection,
                        AUDIT_BACKLOG_SQL,
                        policy.audit,
                        policy.audit
                    ),
                ),
            )
        }

    fun purge(limit: Int): VoucherPoolRetentionReport {
        limit.requireInRange(1, MAX_PURGE_LIMIT, "limit")

        return transaction { connection ->
            val descriptors = purgeDescriptors(connection, limit)
            val inboxAndClaims = purgeTerminalInbox(connection, limit) + purgeTerminalClaims(connection, limit)
            val reservationsAndEntries = purgeTerminalAllocations(connection, limit) +
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

    /** 모든 backup retention과 rehearsal hold가 release된 뒤에만 tenant-lifetime fence를 삭제합니다. */
    fun deleteTenant(tenantId: String): Boolean {
        tenantId.requireNotBlank("tenantId")

        return transaction { connection ->
            if (isTenantHeld(tenantId) || !isTenantBackupDeletionReady(tenantId)) {
                log.warn { "voucher_pool_tenant_delete_held" }
                return@transaction false
            }
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL voucher_pool.retention_purge = 'on'")
                statement.execute("SET CONSTRAINTS ALL DEFERRED")
            }
            VoucherPoolQuarantineTable.deleteWhere { VoucherPoolQuarantineTable.tenantId eq tenantId }
            VoucherPoolRevokePreviewGrantTable.deleteWhere { VoucherPoolRevokePreviewGrantTable.tenantId eq tenantId }
            VoucherPoolReconciliationInboxTable.deleteWhere { VoucherPoolReconciliationInboxTable.tenantId eq tenantId }
            VoucherPoolWorkerClaimTable.deleteWhere { VoucherPoolWorkerClaimTable.tenantId eq tenantId }
            VoucherPoolDepthTable.deleteWhere { VoucherPoolDepthTable.tenantId eq tenantId }
            VoucherPoolAllocationTable.deleteWhere { VoucherPoolAllocationTable.tenantId eq tenantId }
            VoucherPoolReservationTable.deleteWhere { VoucherPoolReservationTable.tenantId eq tenantId }
            VoucherPoolUserLimitTable.deleteWhere { VoucherPoolUserLimitTable.tenantId eq tenantId }
            VoucherPoolAuditTable.deleteWhere { VoucherPoolAuditTable.tenantId eq tenantId }
            VoucherPoolEntryTable.deleteWhere { VoucherPoolEntryTable.tenantId eq tenantId }
            VoucherPoolBatchTable.deleteWhere { VoucherPoolBatchTable.tenantId eq tenantId }
            VoucherPoolCampaignTable.deleteWhere { VoucherPoolCampaignTable.tenantId eq tenantId }
            VoucherPoolHttpIdempotencyTable.deleteWhere { VoucherPoolHttpIdempotencyTable.tenantId eq tenantId }
            VoucherPoolCommandTombstoneTable.deleteWhere { VoucherPoolCommandTombstoneTable.tenantId eq tenantId }
            VoucherPoolCodeDedupTable.deleteWhere { VoucherPoolCodeDedupTable.tenantId eq tenantId }
            VoucherPoolLegalHoldTable.deleteWhere { VoucherPoolLegalHoldTable.tenantId eq tenantId }
            VoucherPoolBackupInventoryTable.deleteWhere { VoucherPoolBackupInventoryTable.tenantId eq tenantId }
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
                FROM candidates WHERE i.ctid=candidates.ctid""".trimIndent(),
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
                DELETE FROM voucher_pool_reconciliation_inbox i USING candidates WHERE i.ctid=candidates.ctid""".trimIndent(),
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
                DELETE FROM voucher_pool_worker_claims w USING candidates WHERE w.ctid=candidates.ctid""".trimIndent(),
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
                DELETE FROM voucher_pool_allocations a USING candidates WHERE a.ctid=candidates.ctid""".trimIndent(),
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
                DELETE FROM voucher_pool_reservations r USING candidates WHERE r.ctid=candidates.ctid""".trimIndent(),
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
                DELETE FROM voucher_pool_entries e USING candidates WHERE e.ctid=candidates.ctid""".trimIndent(),
        ).executeRetention(policy.terminalEntryAndReservation, limit)

    private fun purgeAudits(connection: Connection, limit: Int): Int {
        connection.createStatement().use {
            it.execute("SET LOCAL voucher_pool.retention_purge = 'on'")
        }
        return connection.prepareStatement(
            """WITH candidates AS (
                    SELECT a.ctid FROM voucher_pool_audits a
                    WHERE a.created_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT ${heldTenantSql("a.tenant_id")}
                    ORDER BY a.created_at,a.id
                    LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM voucher_pool_audits a USING candidates WHERE a.ctid=candidates.ctid""".trimIndent(),
        ).executeRetention(policy.audit, limit)
    }

    private fun isTenantHeld(tenantId: String): Boolean {
        val legalHold = VoucherPoolLegalHoldTable
            .select(VoucherPoolLegalHoldTable.holdId)
            .where {
                (VoucherPoolLegalHoldTable.tenantId eq tenantId) and
                        VoucherPoolLegalHoldTable.releasedAt.isNull() and
                        (VoucherPoolLegalHoldTable.holdUntil.isNull() or
                                (VoucherPoolLegalHoldTable.holdUntil greater CurrentTimestamp))
            }
            .limit(1)
            .singleOrNull() != null
        if (legalHold) return true

        return VoucherPoolQuarantineTable
            .select(VoucherPoolQuarantineTable.entryId)
            .where {
                (VoucherPoolQuarantineTable.tenantId eq tenantId) and
                        VoucherPoolQuarantineTable.resolvedAt.isNull()
            }
            .limit(1)
            .singleOrNull() != null
    }

    private fun isTenantBackupDeletionReady(tenantId: String): Boolean {
        val hasBackup = VoucherPoolBackupInventoryTable
            .select(VoucherPoolBackupInventoryTable.backupId)
            .where { VoucherPoolBackupInventoryTable.tenantId eq tenantId }
            .limit(1)
            .singleOrNull() != null
        if (!hasBackup) return false

        return VoucherPoolBackupInventoryTable
            .select(VoucherPoolBackupInventoryTable.backupId)
            .where {
                (VoucherPoolBackupInventoryTable.tenantId eq tenantId) and
                        ((VoucherPoolBackupInventoryTable.retainedUntil greater CurrentTimestamp) or
                                VoucherPoolBackupInventoryTable.restoreRehearsedAt.isNull())
            }
            .limit(1)
            .singleOrNull() == null
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
                     WHERE b.tenant_id=$alias AND b.restore_rehearsed_at IS NULL)""".trimIndent()

    private fun PreparedStatement.executeRetention(duration: Duration, limit: Int): Int =
        use { statement ->
            statement.setLong(1, duration.toMillis())
            statement.setInt(2, limit)
            statement.executeUpdate()
        }

    private fun queryBacklog(
        connection: Connection,
        sql: String,
        vararg durations: Duration,
    ): VoucherPoolRetentionBacklogMetric =
        connection.prepareStatement(sql).use { statement ->
            durations.forEachIndexed { index, duration ->
                statement.setLong(index + 1, duration.toMillis())
            }
            statement.executeQuery().use { result ->
                check(result.next())
                VoucherPoolRetentionBacklogMetric(
                    count = result.getLong("due_count"),
                    oldestAge = result.getLong("oldest_millis").millis(),
                )
            }
        }

    private inline fun <T> transaction(crossinline block: (Connection) -> T): T {
        val database = Database.connect(dataSource)
        return try {
            org.jetbrains.exposed.v1.jdbc.transactions.transaction(database) {
                val connection = TransactionManager.current().connection.connection as Connection
                runCatching { block(connection) }.getOrElse { failure ->
                    log.warn(failure) { "voucher_pool_retention_transaction_failed" }
                    throw failure
                }
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    companion object: KLogging() {
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

    }
}
