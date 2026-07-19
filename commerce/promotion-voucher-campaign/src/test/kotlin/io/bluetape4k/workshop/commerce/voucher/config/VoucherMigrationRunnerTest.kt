package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.io.ClassPathResource
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherMigrationRunnerTest {
    private lateinit var schema: String
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun createIsolatedSchema() {
        schema = "voucher_migration_${UUID.randomUUID().toString().replace("-", "")}".lowercase()
        adminConnection().use { connection ->
            connection.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") }
        }
        dataSource =
            PGSimpleDataSource().apply {
                setURL(postgres.jdbcUrl)
                user = postgres.username ?: PostgreSQLServer.USERNAME
                password = postgres.password ?: PostgreSQLServer.PASSWORD
                currentSchema = schema
            }
    }

    @AfterEach
    fun dropIsolatedSchema() {
        adminConnection().use { connection ->
            connection.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    @Test
    fun `clean migration and same checksum replay are idempotent`() {
        val runner = runner("db/migration/test/V001__voucher_probe.sql")

        runner.migrate() shouldBeEqualTo VoucherMigrationResult.APPLIED
        runner.migrate() shouldBeEqualTo VoucherMigrationResult.ALREADY_APPLIED

        queryLong("SELECT count(*) FROM voucher_schema_history") shouldBeEqualTo 1L
        queryLong("SELECT count(*) FROM voucher_migration_probe") shouldBeEqualTo 0L
    }

    @Test
    fun `production migration creates the complete authority schema and replays`() {
        val runner = runner("db/migration/V001__voucher_campaign.sql")

        runner.migrate() shouldBeEqualTo VoucherMigrationResult.APPLIED
        runner.migrate() shouldBeEqualTo VoucherMigrationResult.ALREADY_APPLIED

        queryLong(
            """
            SELECT count(*)
            FROM information_schema.tables
            WHERE table_schema = '$schema'
              AND table_name IN (
                'voucher_campaigns', 'voucher_claims', 'voucher_reviews', 'voucher_audits',
                'campaign_event_inbox', 'voucher_http_idempotency', 'voucher_schema_history'
              )
            """.trimIndent(),
        ) shouldBeEqualTo 7L
    }

    @Test
    fun `checksum drift fails closed without mutating the applied schema`() {
        runner("db/migration/test/V001__voucher_probe.sql").migrate()

        val failure =
            assertFailsWith<VoucherMigrationException> {
                runner("db/migration/test/V001__voucher_probe_drift.sql").migrate()
            }

        failure.code shouldBeEqualTo VoucherMigrationFailureCode.CHECKSUM_DRIFT
        queryLong("SELECT count(*) FROM voucher_schema_history") shouldBeEqualTo 1L
    }

    @Test
    fun `partial statement failure rolls back schema and history`() {
        val failure =
            assertFailsWith<VoucherMigrationException> {
                runner("db/migration/test/V002__voucher_partial.sql", version = "002").migrate()
            }

        failure.code shouldBeEqualTo VoucherMigrationFailureCode.STATEMENT_FAILED
        queryString("SELECT to_regclass('$schema.voucher_migration_partial')").shouldBeNull()
        queryString("SELECT to_regclass('$schema.voucher_schema_history')").shouldBeNull()

        runner("db/migration/test/V001__voucher_probe.sql", version = "002").migrate() shouldBeEqualTo
            VoucherMigrationResult.APPLIED
        queryLong("SELECT count(*) FROM voucher_schema_history") shouldBeEqualTo 1L
    }

    @Test
    fun `held advisory lock fails with a sanitized lock timeout`() {
        adminConnection().use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_advisory_xact_lock($MIGRATION_LOCK_KEY)").use { result -> result.next() }
            }

            val failure =
                assertFailsWith<VoucherMigrationException> {
                    runner(
                        resource = "db/migration/test/V001__voucher_probe.sql",
                        lockTimeout = Duration.ofMillis(25),
                    ).migrate()
                }

            failure.code shouldBeEqualTo VoucherMigrationFailureCode.LOCK_TIMEOUT
            blocker.rollback()
        }
    }

    @Test
    fun `contending startup waits for the winner then rechecks the checksum`() {
        adminConnection().use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_advisory_xact_lock($MIGRATION_LOCK_KEY)").use { it.next() }
            }
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val migration =
                    executor.submit<VoucherMigrationResult> {
                        runner(
                            resource = "db/migration/test/V001__voucher_probe.sql",
                            lockTimeout = Duration.ofSeconds(2),
                        ).migrate()
                    }
                TimeUnit.MILLISECONDS.sleep(50)
                migration.isDone shouldBeEqualTo false
                blocker.rollback()

                migration.get(2, TimeUnit.SECONDS) shouldBeEqualTo VoucherMigrationResult.APPLIED
            }
        }
    }

    private fun runner(
        resource: String,
        version: String = "001",
        lockTimeout: Duration = Duration.ofSeconds(1),
    ): VoucherMigrationRunner =
        VoucherMigrationRunner(
            dataSource = dataSource,
            migration = VoucherMigration(version, ClassPathResource(resource)),
            advisoryLockKey = MIGRATION_LOCK_KEY,
            lockTimeout = lockTimeout,
        )

    private fun queryLong(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    private fun queryString(sql: String): String? =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    result.next()
                    result.getString(1)
                }
            }
        }

    private fun adminConnection() =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )

    companion object {
        private const val MIGRATION_LOCK_KEY = 534001L
        private val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres
    }
}
