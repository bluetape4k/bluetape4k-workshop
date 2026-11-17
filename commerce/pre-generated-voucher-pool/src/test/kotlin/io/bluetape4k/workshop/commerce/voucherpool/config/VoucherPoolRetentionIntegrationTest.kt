@file:Suppress("MagicNumber", "MaxLineLength", "SqlSourceToSinkFlow")

package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeZero
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAuditTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBackupInventoryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCodeDedupTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCommandTombstoneTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolEntryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolHttpIdempotencyTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolReconciliationInboxTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolReservationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolWorkerClaimTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.core.io.ClassPathResource
import java.sql.Connection
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolRetentionIntegrationTest {

    companion object: KLogging()

    private lateinit var voucherSchema: String
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var retention: VoucherPoolRetention
    private val policy = VoucherPoolRetentionPolicy()

    @BeforeAll
    fun createSchema() {
        voucherSchema = "voucher_retention_${Base58.randomString(8).lowercase()}"
        VOUCHER_POOL_TASK_12_POSTGRES.createSchema(voucherSchema)
        dataSource = postgresDataSource(voucherSchema)
        database = Database.connect(dataSource)
        migrationRunner(dataSource).migrate()
        retention = VoucherPoolRetention(dataSource, policy)
    }

    @AfterAll
    fun dropSchema() {
        VOUCHER_POOL_TASK_12_POSTGRES.dropSchema(voucherSchema)
        TransactionManager.closeAndUnregister(database)
        dataSource.close()
    }

    @ParameterizedTest
    @CsvSource(
        "DESCRIPTOR,24,hours",
        "TERMINAL_INBOX_CLAIM,7,days",
        "TERMINAL_ENTRY_RESERVATION,30,days",
        "AUDIT,90,days",
    )
    fun `retention boundary uses database time`(kind: RetentionKind, amount: Long, unit: String) {
        val tenantBefore = "before-${kind.name.lowercase()}"
        val tenantAt = "at-${kind.name.lowercase()}"

        try {
            seed(kind, tenantBefore, "$amount $unit - 1 second")
            retention.purge(limit = 100)
            retainedCount(kind, tenantBefore) shouldBeEqualTo expectedRows(kind)

            seed(kind, tenantAt, "$amount $unit")
            retention.purge(limit = 100)
            retainedCount(kind, tenantAt) shouldBeEqualTo 0L
        } finally {
            deleteFixtureTenant(tenantBefore)
            deleteFixtureTenant(tenantAt)
        }
        backupCount(setOf(tenantBefore, tenantAt)).shouldBeZero()
    }

    @Test
    fun `purge pauses for legal hold and active quarantine`() {
        val heldTenant = "legal-hold"
        seed(RetentionKind.DESCRIPTOR, heldTenant, "25 hours")
        execute(
            "INSERT INTO voucher_pool_legal_holds(tenant_id,reason_code) " +
                    "VALUES ('$heldTenant','LEGAL_REQUEST')",
        )

        retention.purge(limit = 100)

        retainedCount(RetentionKind.DESCRIPTOR, heldTenant) shouldBeEqualTo 1L

        val quarantinedTenant = "quarantine-hold"
        val entry = seedTerminalEntry(quarantinedTenant, "31 days")
        execute(
            """
                INSERT INTO voucher_pool_quarantines
                (tenant_id,entry_id,source_state,source_revision,reason_code)
                VALUES ('$quarantinedTenant','$entry','EXPIRED',0,'INVALID_TAG')
                """.trimIndent(),
        )

        retention.purge(limit = 100)

        retainedCount(RetentionKind.TERMINAL_ENTRY_RESERVATION, quarantinedTenant) shouldBeEqualTo 2L
    }

    @Test
    fun `purge remains held until a backup restore rehearsal passes`() {
        val tenant = "restore-rehearsal-hold"
        seed(RetentionKind.DESCRIPTOR, tenant, "25 hours", backupReady = false)

        retention.purge(limit = 100)

        retainedCount(RetentionKind.DESCRIPTOR, tenant) shouldBeEqualTo 1L

        seedRehearsedBackup(tenant)
        retention.purge(limit = 100)

        retainedCount(RetentionKind.DESCRIPTOR, tenant) shouldBeEqualTo 0L
    }

    @Test
    fun `purge preserves dependency order and tenant lifetime replay fences`() {
        val tenant = "dependency-order"
        seed(RetentionKind.DESCRIPTOR, tenant, "25 hours")
        seed(RetentionKind.TERMINAL_INBOX_CLAIM, tenant, "8 days")
        seed(RetentionKind.TERMINAL_ENTRY_RESERVATION, tenant, "31 days")
        seed(RetentionKind.AUDIT, tenant, "91 days")

        val report = retention.purge(limit = 100)

        log.debug { "report: $report" }
        report.stages shouldBeEqualTo RetentionStage.entries
        report.count(RetentionStage.DESCRIPTORS) shouldBeEqualTo 1
        report.count(RetentionStage.TERMINAL_INBOX_AND_CLAIMS) shouldBeEqualTo 2
        report.count(RetentionStage.TERMINAL_RESERVATIONS_AND_ENTRIES) shouldBeEqualTo 2
        report.count(RetentionStage.AUDIT) shouldBeEqualTo 1

        tombstoneCount(tenant) shouldBeEqualTo 1L
        codeDedupCount(tenant) shouldBeEqualTo 1L
    }

    @Test
    fun `backlog metrics expose due count and oldest overdue age with bounded kinds`() {
        val tenant = "retention-metrics"
        seed(RetentionKind.DESCRIPTOR, tenant, "25 hours")
        seed(RetentionKind.TERMINAL_INBOX_CLAIM, tenant, "8 days")
        seed(RetentionKind.TERMINAL_ENTRY_RESERVATION, tenant, "31 days")
        seed(RetentionKind.AUDIT, tenant, "91 days")

        val backlog = retention.backlog()

        log.debug { "backlog: $backlog" }
        backlog[RetentionKind.DESCRIPTOR].count shouldBeGreaterThan 0L
        backlog[RetentionKind.TERMINAL_INBOX_CLAIM].count shouldBeGreaterThan 1L
        backlog[RetentionKind.TERMINAL_ENTRY_RESERVATION].count shouldBeGreaterThan 1L
        backlog[RetentionKind.AUDIT].count shouldBeGreaterThan 0L

        RetentionKind.entries.forEach { kind ->
            backlog[kind].oldestAge.toMillis() shouldBeGreaterThan 0L
        }
    }

    @Test
    fun `audit cursor remains monotonic after retained history is purged`() {
        val tenant = "cursor-restart"
        seed(RetentionKind.AUDIT, tenant, "91 days")
        val oldCursor = maxAuditId(tenant)

        retention.purge(limit = 100)
        seed(RetentionKind.AUDIT, tenant, "1 second")
        val restartedCursor = maxAuditId(tenant)

        restartedCursor shouldBeGreaterThan oldCursor
    }

    @Test
    fun `concurrent replay lookup and descriptor purge always retain a terminal decision`() {
        val tenant = "concurrent-replay-purge"
        seed(RetentionKind.DESCRIPTOR, tenant, "25 hours")
        val invocation = AtomicInteger()
        val invalid = AtomicInteger()

        MultithreadingTester()
            .workers(12)
            .rounds(5)
            .add {
                if (invocation.incrementAndGet() % 3 == 0) {
                    retention.purge(limit = 1)
                } else if (!hasReplayDecision(tenant)) {
                    invalid.incrementAndGet()
                }
            }
            .run()

        invalid.get() shouldBeEqualTo 0
        hasReplayDecision(tenant).shouldBeTrue()

        descriptorlessIdempotencyCount(tenant) shouldBeEqualTo 1L
    }

    @Test
    fun `backup retention blocks irreversible tenant deletion`() {
        val tenant = "tenant-delete"
        seed(RetentionKind.DESCRIPTOR, tenant, "25 hours")
        execute(
            """
                INSERT INTO voucher_pool_backup_inventory
                (backup_id,tenant_id,kek_versions,verification_versions,user_identity_versions,audit_versions,
                 signature_versions,stable_dedup_version,
                 command_tombstone_version,retained_until,restore_rehearsed_at)
                VALUES ('${UUID.randomUUID()}','$tenant',ARRAY['kek-v1'],ARRAY['11'],ARRAY['12'],ARRAY['15'],
                        ARRAY[]::text[],'1','2',
                        statement_timestamp()+interval '1 day',statement_timestamp())
                """.trimIndent(),
        )
        retention.deleteTenant(tenant).shouldBeFalse()

        execute("UPDATE voucher_pool_backup_inventory SET retained_until=statement_timestamp() WHERE tenant_id='$tenant'")
        retention.deleteTenant(tenant).shouldBeTrue()

        tombstoneCount(tenant) shouldBeEqualTo 0L
    }

    @Test
    fun `tenant deletion fails closed when no verified backup inventory exists`() {
        val tenant = "tenant-delete-without-backup"
        seed(RetentionKind.DESCRIPTOR, tenant, "25 hours")
        execute("DELETE FROM voucher_pool_backup_inventory WHERE tenant_id='$tenant'")

        retention.deleteTenant(tenant).shouldBeFalse()
        tombstoneCount(tenant) shouldBeEqualTo 1L
    }

    private fun seed(kind: RetentionKind, tenant: String, age: String, backupReady: Boolean = true) {
        if (backupReady) seedRehearsedBackup(tenant)

        when (kind) {
            RetentionKind.DESCRIPTOR           -> seedDescriptor(tenant, age)
            RetentionKind.TERMINAL_INBOX_CLAIM -> seedInboxAndClaim(tenant, age)
            RetentionKind.TERMINAL_ENTRY_RESERVATION -> seedTerminalEntry(tenant, age)
            RetentionKind.AUDIT                -> seedAudit(tenant, age)
        }
    }

    private fun seedDescriptor(tenant: String, age: String) {
        val effect = UUID.randomUUID()
        val expiresAt =
            "statement_timestamp()-interval '$age'+(${policy.descriptor.toMillis()} * interval '1 millisecond')"

        execute(
            """
                INSERT INTO voucher_pool_http_idempotency
                (tenant_id,operation,scoped_key_digest,fingerprint,status,command_deadline,descriptor,completed_at,expires_at)
                VALUES ('$tenant','reserve',decode('01','hex'),decode('02','hex'),'COMPLETED',
                        statement_timestamp()+interval '1 hour','{"status":201,"outcome":"CREATED","effectId":"$effect","revision":1}',
                        statement_timestamp()-interval '$age',$expiresAt)
                """.trimIndent(),
        )
        execute(
            """
                INSERT INTO voucher_pool_command_tombstones
                (tenant_id,operation,key_version,scoped_key_digest,fingerprint,effect_id)
                VALUES ('$tenant','reserve',2,decode('01','hex'),decode('02','hex'),'$effect')
                """.trimIndent(),
        )
    }

    private fun seedInboxAndClaim(tenant: String, age: String) {
        execute(
            """
                INSERT INTO voucher_pool_reconciliation_inbox
                (tenant_id,event_id,payload_digest,status,next_attempt_at,terminal_outcome)
                VALUES ('$tenant','${UUID.randomUUID()}',decode('03','hex'),'APPLIED',
                        statement_timestamp()-interval '$age','APPLIED')
                """.trimIndent(),
        )
        execute(
            """
                INSERT INTO voucher_pool_worker_claims
                (tenant_id,worker_type,scope_id,next_attempt_at,checkpoint)
                VALUES ('$tenant','PURGE','${UUID.randomUUID()}',statement_timestamp()-interval '$age',1)
                """.trimIndent(),
        )
    }

    private fun seedTerminalEntry(tenant: String, age: String): UUID {
        val campaign = UUID.randomUUID()
        val batch = UUID.randomUUID()
        val entry = UUID.randomUUID()
        val reservation = UUID.randomUUID()
        execute(
            """
                INSERT INTO voucher_pool_campaigns
                (tenant_id,campaign_id,state,user_identity_key_version,policy_version)
                VALUES ('$tenant','$campaign','REVOKED',12,1)
                """.trimIndent(),
        )
        execute(
            """
                INSERT INTO voucher_pool_batches
                 (tenant_id,batch_id,campaign_id,state,source_kind,provenance_digest,request_fingerprint,
                 policy_version,activates_at,expected_count,next_source_ordinal,accepted_count)
                VALUES ('$tenant','$batch','$campaign','EXPIRED','GENERATED',decode('04','hex'),decode('05','hex'),
                        1,statement_timestamp()-interval '40 days',1,1,1)
                """.trimIndent(),
        )
        execute(
            """
                INSERT INTO voucher_pool_entries
                 (tenant_id,entry_id,campaign_id,batch_id,source_ordinal,state,stable_dedup_digest,
                 code_ciphertext,code_nonce,wrapped_dek,wrap_nonce,kek_version,terminal_reason,created_at,updated_at)
                VALUES ('$tenant','$entry','$campaign','$batch',0,'EXPIRED',decode('06','hex'),
                        decode('07','hex'),decode('080808080808080808080808','hex'),decode('09','hex'),
                        decode('0a0a0a0a0a0a0a0a0a0a0a0a','hex'),'kek-v1','TTL_EXPIRED',
                        statement_timestamp()-interval '$age',
                        statement_timestamp()-interval '$age')
                """.trimIndent(),
        )
        execute(
            """INSERT INTO voucher_pool_code_dedup
                (tenant_id,stable_dedup_digest,first_campaign_id,first_batch_id,first_entry_id,key_version)
                VALUES ('$tenant',decode('06','hex'),'$campaign','$batch','$entry',1)""".trimIndent(),
        )
        execute(
            """
                INSERT INTO voucher_pool_reservations
                 (tenant_id,reservation_id,campaign_id,batch_id,entry_id,user_digest,idempotency_owner_digest,
                 state,reservation_expires_at,policy_version)
                VALUES ('$tenant','$reservation','$campaign','$batch','$entry',decode('0b','hex'),decode('0c','hex'),
                        'EXPIRED',statement_timestamp()-interval '$age',1)
                """.trimIndent(),
        )
        return entry
    }

    private fun seedAudit(tenant: String, age: String) {
        val campaign = UUID.randomUUID()
        execute(
            """
                INSERT INTO voucher_pool_campaigns
                (tenant_id,campaign_id,state,user_identity_key_version,policy_version)
                VALUES ('$tenant','$campaign','REVOKED',12,1)
                """.trimIndent(),
        )
        execute(
            """
                INSERT INTO voucher_pool_audits
                (tenant_id,campaign_id,aggregate_type,aggregate_id,revision,policy_version,actor_type,
                 reason_code,audit_key_version,created_at)
                VALUES ('$tenant','$campaign','CAMPAIGN','$campaign',0,1,'SYSTEM','RETENTION_FIXTURE',15,
                        statement_timestamp()-interval '$age')
                """.trimIndent(),
        )
    }

    private fun seedRehearsedBackup(tenant: String) {
        execute(
            """
                INSERT INTO voucher_pool_backup_inventory
                (backup_id,tenant_id,kek_versions,verification_versions,user_identity_versions,audit_versions,
                 signature_versions,stable_dedup_version,command_tombstone_version,retained_until,restore_rehearsed_at)
                VALUES ('${UUID.randomUUID()}','$tenant',ARRAY['kek-v1'],ARRAY['11'],ARRAY['12'],ARRAY['15'],
                        ARRAY[]::text[],'1','2',statement_timestamp()+interval '1 day',statement_timestamp())
                """.trimIndent(),
        )
    }

    private fun retainedCount(kind: RetentionKind, tenant: String): Long =
        when (kind) {
            RetentionKind.DESCRIPTOR                 ->
                transaction(database) {
                    VoucherPoolHttpIdempotencyTable
                        .selectAll()
                        .where {
                            (VoucherPoolHttpIdempotencyTable.tenantId eq tenant) and
                                    VoucherPoolHttpIdempotencyTable.descriptor.isNotNull()
                        }
                        .count()
                }
            RetentionKind.TERMINAL_INBOX_CLAIM       ->
                transaction(database) {
                    VoucherPoolReconciliationInboxTable.selectAll()
                        .where { VoucherPoolReconciliationInboxTable.tenantId eq tenant }
                        .count() +
                            VoucherPoolWorkerClaimTable.selectAll()
                                .where { VoucherPoolWorkerClaimTable.tenantId eq tenant }
                                .count()
                }
            RetentionKind.TERMINAL_ENTRY_RESERVATION ->
                transaction(database) {
                    VoucherPoolEntryTable.selectAll()
                        .where { VoucherPoolEntryTable.tenantId eq tenant }
                        .count() +
                            VoucherPoolReservationTable.selectAll()
                                .where { VoucherPoolReservationTable.tenantId eq tenant }
                                .count()
                }
            RetentionKind.AUDIT                      ->
                transaction(database) {
                    VoucherPoolAuditTable.selectAll().where { VoucherPoolAuditTable.tenantId eq tenant }.count()
                }
        }

    private fun expectedRows(kind: RetentionKind): Long =
        if (kind == RetentionKind.TERMINAL_INBOX_CLAIM || kind == RetentionKind.TERMINAL_ENTRY_RESERVATION) 2L else 1L

    private fun deleteFixtureTenant(tenant: String) {
        if (backupCount(setOf(tenant)) == 0L) return
        execute(
            "UPDATE voucher_pool_backup_inventory " +
                    "SET retained_until=statement_timestamp() WHERE tenant_id='$tenant'",
        )
        retention.deleteTenant(tenant).shouldBeTrue()
    }

    private fun execute(sql: String) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(sql)
            }
        }
    }

    private fun backupCount(tenants: Set<String>): Long = transaction(database) {
        VoucherPoolBackupInventoryTable
            .selectAll()
            .where { VoucherPoolBackupInventoryTable.tenantId inList tenants.toList() }
            .count()
    }

    private fun tombstoneCount(tenant: String): Long = transaction(database) {
        VoucherPoolCommandTombstoneTable
            .selectAll()
            .where { VoucherPoolCommandTombstoneTable.tenantId eq tenant }
            .count()
    }

    private fun codeDedupCount(tenant: String): Long = transaction(database) {
        VoucherPoolCodeDedupTable
            .selectAll()
            .where { VoucherPoolCodeDedupTable.tenantId eq tenant }
            .count()
    }

    private fun maxAuditId(tenant: String): Long = transaction(database) {
        VoucherPoolAuditTable
            .selectAll()
            .where { VoucherPoolAuditTable.tenantId eq tenant }
            .maxOfOrNull { it[VoucherPoolAuditTable.id] }
            ?: 0L
    }

    private fun descriptorlessIdempotencyCount(tenant: String): Long = transaction(database) {
        VoucherPoolHttpIdempotencyTable
            .selectAll()
            .where {
                (VoucherPoolHttpIdempotencyTable.tenantId eq tenant) and
                        VoucherPoolHttpIdempotencyTable.descriptor.isNull()
            }
            .count()
    }

    @Suppress("SameParameterValue")
    private fun hasReplayDecision(tenant: String): Boolean = transaction(database) {
        val idempotencyRows = VoucherPoolHttpIdempotencyTable
            .selectAll()
            .where { VoucherPoolHttpIdempotencyTable.tenantId eq tenant }
            .map {
                ReplayOwner(
                    operation = it[VoucherPoolHttpIdempotencyTable.operation],
                    scopedKeyDigest = it[VoucherPoolHttpIdempotencyTable.scopedKeyDigest],
                    hasDescriptor = it[VoucherPoolHttpIdempotencyTable.descriptor] != null,
                )
            }
        val terminalRows = VoucherPoolCommandTombstoneTable
            .selectAll()
            .where { VoucherPoolCommandTombstoneTable.tenantId eq tenant }
            .map {
                ReplayTombstone(
                    operation = it[VoucherPoolCommandTombstoneTable.operation],
                    scopedKeyDigest = it[VoucherPoolCommandTombstoneTable.scopedKeyDigest],
                    hasTerminalEffect = it[VoucherPoolCommandTombstoneTable.effectId] != null ||
                            it[VoucherPoolCommandTombstoneTable.terminalCode] != null,
                )
            }
        idempotencyRows.any { owner ->
            terminalRows.any { tombstone ->
                owner.operation == tombstone.operation &&
                        owner.scopedKeyDigest.contentEquals(tombstone.scopedKeyDigest) &&
                        (owner.hasDescriptor || tombstone.hasTerminalEffect)
            }
        }
    }

    private data class ReplayOwner(
        val operation: String,
        val scopedKeyDigest: ByteArray,
        val hasDescriptor: Boolean,
    )

    private data class ReplayTombstone(
        val operation: String,
        val scopedKeyDigest: ByteArray,
        val hasTerminalEffect: Boolean,
    )
}

internal fun migrationRunner(dataSource: DataSource): VoucherPoolMigrationRunner =
    VoucherPoolMigrationRunner(
        dataSource,
        VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
        537_012L,
    )

internal fun postgresDataSource(schema: String): HikariDataSource =
    VOUCHER_POOL_TASK_12_POSTGRES.getHikariDataSource {
        this.schema = schema
    }

internal fun PostgreSQLServer.createSchema(schema: String) {
    adminConnection().use { connection ->
        connection.createStatement().use {
            it.execute("CREATE SCHEMA $schema")
        }
    }
}

internal fun PostgreSQLServer.dropSchema(schema: String) {
    adminConnection().use { connection ->
        connection.createStatement().use {
            it.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }
}

private fun PostgreSQLServer.adminConnection(): Connection =
    java.sql.DriverManager.getConnection(
        jdbcUrl,
        username ?: PostgreSQLServer.USERNAME,
        password ?: PostgreSQLServer.PASSWORD,
    )

internal val VOUCHER_POOL_TASK_12_POSTGRES: PostgreSQLServer = PostgreSQLServer.Launcher.postgres
