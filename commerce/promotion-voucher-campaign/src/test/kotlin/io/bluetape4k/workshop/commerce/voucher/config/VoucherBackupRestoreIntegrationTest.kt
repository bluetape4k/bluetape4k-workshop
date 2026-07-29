package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.Uuid
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.io.ClassPathResource
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

internal class VoucherBackupRestoreIntegrationTest : VoucherCompatibilityTestSupport() {
    private var restoreSchema: String? = null

    @Test
    fun `PostgreSQL clone restores audit inbox publication replay and key references`() {
        migrationRunner().migrate()
        seedAuthorityRows()
        val cloneSchema = "voucher_restore_${Base58.randomString(8).lowercase()}"
        restoreSchema = cloneSchema
        createSchema(cloneSchema)
        val clone = cloneDataSource(cloneSchema)
        VoucherMigrationRunner(
            dataSource = clone,
            migration = VoucherMigration("001", ClassPathResource("db/migration/V001__voucher_campaign.sql")),
            advisoryLockKey = 534_010L,
        ).migrate()
        createPublicationFixture(schema)
        createPublicationFixture(cloneSchema)

        copyTables(cloneSchema)

        queryLong(clone, "SELECT max(revision) FROM voucher_audits") shouldBeEqualTo 7L
        queryLong(clone, "SELECT count(*) FROM campaign_event_inbox") shouldBeEqualTo 1L
        queryLong(clone, "SELECT count(*) FROM event_publication") shouldBeEqualTo 1L
        queryLong(clone, "SELECT count(*) FROM voucher_http_idempotency WHERE status = 'COMPLETED'") shouldBeEqualTo 1L
        PostgresReferencedKeyVersionSource(clone).referencedVersions() shouldBeEqualTo
            ReferencedKeyVersions(generation = setOf(5), verification = setOf(7))
    }

    @AfterEach
    fun dropRestoreSchema() {
        restoreSchema?.let { schemaName ->
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS ${quoted(schemaName)} CASCADE") }
            }
        }
    }

    private fun seedAuthorityRows() {
        val campaignId = Uuid.V7.nextId()
        val claimId = Uuid.V7.nextId()
        val now = Instant.parse("2026-07-20T00:00:00Z")
        dataSource.connection.use { connection ->
            val campaignRowId =
                connection.prepareStatement(
                    """
                    INSERT INTO voucher_campaigns (
                        tenant_id, campaign_id, state, starts_at, ends_at, capacity, allocated_count,
                        per_user_limit, redemption_ttl_seconds, policy_version, revision
                    ) VALUES (?, ?, 'ACTIVE', ?, ?, 10, 1, 1, 3600, 1, 7)
                    RETURNING id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, TENANT)
                    statement.setObject(2, campaignId)
                    statement.setTimestamp(3, Timestamp.from(now.minusSeconds(60)))
                    statement.setTimestamp(4, Timestamp.from(now.plusSeconds(3600)))
                    statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
                }
            connection.prepareStatement(
                """
                INSERT INTO voucher_claims (
                    tenant_id, campaign_row_id, campaign_id, claim_id, allocation_id, user_digest,
                    state, capacity_reserved, allocation_policy_version, code_verifier,
                    generation_key_version, verification_key_version, expires_at, revision
                ) VALUES (?, ?, ?, ?, ?, ?, 'ALLOCATED', true, 1, ?, 5, 7, ?, 0)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, TENANT)
                statement.setLong(2, campaignRowId)
                statement.setObject(3, campaignId)
                statement.setObject(4, claimId)
                statement.setObject(5, Uuid.V7.nextId())
                statement.setString(6, "a".repeat(64))
                statement.setBytes(7, ByteArray(32) { 9 })
                statement.setTimestamp(8, Timestamp.from(now.plusSeconds(3600)))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO voucher_audits (
                    tenant_id, campaign_id, aggregate_type, aggregate_id, revision,
                    actor_type, reason_code, policy_version
                ) VALUES (?, ?, 'CAMPAIGN', ?, 7, 'OPERATOR', 'BACKUP_SMOKE', 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, TENANT)
                statement.setObject(2, campaignId)
                statement.setObject(3, campaignId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO campaign_event_inbox (
                    tenant_id, event_id, aggregate_type, aggregate_id, payload_digest,
                    observed_sequence, status, next_attempt_at
                ) VALUES (?, ?, 'CAMPAIGN', ?, ?, 7, 'APPLIED', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, TENANT)
                statement.setObject(2, Uuid.V7.nextId())
                statement.setObject(3, campaignId)
                statement.setString(4, "b".repeat(64))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO voucher_http_idempotency (
                    tenant_id, principal_digest, operation, resource_id, key_digest,
                    request_fingerprint, status, command_deadline, response_kind, response_status,
                    aggregate_id, generation_key_version, verification_key_version, expires_at
                ) VALUES (?, ?, 'allocate', ?, ?, ?, 'COMPLETED', ?, 'ALLOCATED', 201, ?, 5, 7, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, TENANT)
                statement.setString(2, "c".repeat(43))
                statement.setString(3, campaignId.toString())
                statement.setString(4, "d".repeat(43))
                statement.setString(5, "e".repeat(43))
                statement.setTimestamp(6, Timestamp.from(now.plusSeconds(30)))
                statement.setObject(7, claimId)
                statement.setTimestamp(8, Timestamp.from(now.plusSeconds(7200)))
                statement.executeUpdate()
            }
        }
    }

    private fun createSchema(schemaName: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA ${quoted(schemaName)}") }
        }
    }

    private fun createPublicationFixture(schemaName: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS ${quoted(schemaName)}.event_publication " +
                        "(id UUID PRIMARY KEY, event_type VARCHAR(128) NOT NULL)",
                )
                if (schemaName == schema) {
                    statement.execute(
                        "INSERT INTO ${quoted(schemaName)}.event_publication VALUES ('${Uuid.V7.nextId()}', 'VoucherEvent')",
                    )
                }
            }
        }
    }

    private fun copyTables(cloneSchema: String) {
        val tables =
            listOf(
                "voucher_campaigns",
                "voucher_claims",
                "voucher_audits",
                "campaign_event_inbox",
                "voucher_http_idempotency",
                "event_publication",
            )
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                tables.forEach { table ->
                    statement.execute(
                        "INSERT INTO ${quoted(cloneSchema)}.$table SELECT * FROM ${quoted(schema)}.$table",
                    )
                }
            }
        }
    }

    private fun cloneDataSource(schemaName: String): DataSource =
        PGSimpleDataSource().apply {
            setURL(compatibilityJdbcUrl().substringBefore("currentSchema=") + "currentSchema=$schemaName")
            user = postgresUsername()
            password = postgresPassword()
            currentSchema = schemaName
        }

    private fun queryLong(
        source: DataSource,
        sql: String,
    ): Long =
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getLong(1) }
            }
        }

    private fun quoted(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    private companion object {
        const val TENANT = "backup-restore"
    }
}
