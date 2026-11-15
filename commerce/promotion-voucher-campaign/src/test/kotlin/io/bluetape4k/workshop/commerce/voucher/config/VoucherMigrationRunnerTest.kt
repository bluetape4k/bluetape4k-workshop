package io.bluetape4k.workshop.commerce.voucher.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.jdbc.sql.singleLong
import io.bluetape4k.jdbc.sql.singleString
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.core.io.ClassPathResource
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherMigrationRunnerTest {

    companion object: KLogging() {
        private const val MIGRATION_LOCK_KEY = 534001L
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }

    private lateinit var voucherSchema: String
    private lateinit var dataSource: HikariDataSource

    @BeforeEach
    fun createIsolatedSchema() {
        voucherSchema = "voucher_migration_${Base58.randomString(8).lowercase()}"
        adminConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA $voucherSchema")
            }
        }
        dataSource = postgres.getHikariDataSource {
            schema = voucherSchema
        }
    }

    @AfterEach
    fun dropIsolatedSchema() {
        adminConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP SCHEMA IF EXISTS $voucherSchema CASCADE")
            }
        }
        dataSource.close()
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
            WHERE table_schema = '$voucherSchema'
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

        val failure = assertFailsWith<VoucherMigrationException> {
            runner("db/migration/test/V001__voucher_probe_drift.sql").migrate()
        }

        failure.code shouldBeEqualTo VoucherMigrationFailureCode.CHECKSUM_DRIFT
        queryLong("SELECT count(*) FROM voucher_schema_history") shouldBeEqualTo 1L
    }

    @Test
    fun `partial statement failure rolls back schema and history`() {
        val failure = assertFailsWith<VoucherMigrationException> {
            runner("db/migration/test/V002__voucher_partial.sql", version = "002").migrate()
        }

        failure.code shouldBeEqualTo VoucherMigrationFailureCode.STATEMENT_FAILED

        queryString("SELECT to_regclass('$voucherSchema.voucher_migration_partial')").shouldBeNull()
        queryString("SELECT to_regclass('$voucherSchema.voucher_schema_history')").shouldBeNull()

        runner(
            "db/migration/test/V001__voucher_probe.sql",
            version = "002"
        ).migrate() shouldBeEqualTo VoucherMigrationResult.APPLIED

        queryLong("SELECT count(*) FROM voucher_schema_history") shouldBeEqualTo 1L
    }

    @Test
    fun `held advisory lock fails with a sanitized lock timeout`() {
        adminConnection().use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT pg_advisory_xact_lock($MIGRATION_LOCK_KEY)")
                    .use { result -> result.next() }
            }

            val failure = assertFailsWith<VoucherMigrationException> {
                runner(
                    resource = "db/migration/test/V001__voucher_probe.sql",
                    lockTimeout = 25.millis(),
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
            blocker.createStatement().use { stmt ->
                stmt.executeQuery("SELECT pg_advisory_xact_lock($MIGRATION_LOCK_KEY)").use { it.next() }
            }
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val migration = executor.submit<VoucherMigrationResult> {
                    runner(
                        resource = "db/migration/test/V001__voucher_probe.sql",
                        lockTimeout = 2.seconds(),
                    ).migrate()
                }
                TimeUnit.MILLISECONDS.sleep(50)
                migration.isDone.shouldBeFalse()
                blocker.rollback()

                migration.get(2.seconds) shouldBeEqualTo VoucherMigrationResult.APPLIED
            }
        }
    }

    private fun runner(
        resource: String,
        version: String = "001",
        lockTimeout: Duration = 1.seconds(),
    ): VoucherMigrationRunner =
        VoucherMigrationRunner(
            dataSource = dataSource,
            migration = VoucherMigration(version, ClassPathResource(resource)),
            advisoryLockKey = MIGRATION_LOCK_KEY,
            lockTimeout = lockTimeout,
        )

    private fun queryLong(sql: String): Long =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { result ->
                    result.singleLong()
                }
            }
        }

    private fun queryString(sql: String): String? =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { result ->
                    result.singleString()
                }
            }
        }

    private fun adminConnection(): Connection =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )
}
