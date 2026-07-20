@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
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
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolMigrationRunnerTest {
    private lateinit var schema: String
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun createSchema() {
        schema = "voucher_pool_${Base58.randomString(8).lowercase()}"
        adminConnection().use { it.createStatement().execute("CREATE SCHEMA $schema") }
        dataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username ?: PostgreSQLServer.USERNAME
            password = postgres.password ?: PostgreSQLServer.PASSWORD
            currentSchema = schema
        }
    }

    @AfterEach
    fun dropSchema() {
        adminConnection().use { it.createStatement().execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    @Test
    fun `production migration is complete idempotent and checksum guarded`() {
        val runner = runner()
        runner.migrate() shouldBeEqualTo VoucherPoolMigrationResult.APPLIED
        runner.migrate() shouldBeEqualTo VoucherPoolMigrationResult.ALREADY_APPLIED
        queryLong(
            """SELECT count(*) FROM information_schema.tables WHERE table_schema='$schema' AND table_name LIKE 'voucher_pool_%'""",
        ) shouldBeEqualTo 15L
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE voucher_pool_schema_history SET checksum='drift' WHERE version='001'").executeUpdate()
        }
        assertFailsWith<VoucherPoolMigrationException> { runner.migrate() }.code shouldBeEqualTo
            VoucherPoolMigrationFailureCode.CHECKSUM_DRIFT
    }

    @Test
    fun `production migration exposes complete authority metadata`() {
        runner().migrate()

        queryLong(
            """SELECT count(*) FROM information_schema.columns WHERE table_schema='$schema' AND
                ((table_name='voucher_pool_entries' AND column_name IN ('code_nonce','wrap_nonce')) OR
                 (table_name='voucher_pool_audits' AND column_name='request_digest') OR
                 (table_name='voucher_pool_worker_claims' AND column_name IN
                    ('attempt','next_attempt_at','checkpoint','poison_reason')))""",
        ) shouldBeEqualTo 7L
        queryLong(
            """SELECT count(*) FROM pg_indexes WHERE schemaname='$schema' AND
                indexname='uq_voucher_pool_reservation_active_entry' AND indexdef LIKE '%WHERE%state%ACTIVE%'""",
        ) shouldBeEqualTo 1L
        queryLong(
            """SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
                JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='$schema'
                AND c.contype='f' AND t.relname IN ('voucher_pool_entries','voucher_pool_reservations','voucher_pool_allocations')""",
        ) shouldBeEqualTo 9L
    }

    @Test
    fun `held advisory startup lock times out without schema mutation`() {
        adminConnection().use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().executeQuery("SELECT pg_advisory_xact_lock($LOCK_KEY)").use { it.next() }
            assertFailsWith<VoucherPoolMigrationException> {
                runner(Duration.ofMillis(30)).migrate()
            }.code shouldBeEqualTo VoucherPoolMigrationFailureCode.LOCK_TIMEOUT
            blocker.rollback()
        }
        queryLong("SELECT count(*) FROM information_schema.tables WHERE table_schema='$schema'") shouldBeEqualTo 0L
    }

    private fun runner(timeout: Duration = Duration.ofSeconds(2)) =
        VoucherPoolMigrationRunner(
            dataSource,
            VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            LOCK_KEY,
            timeout,
        )

    private fun queryLong(sql: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().executeQuery(sql).use { result -> result.next(); result.getLong(1) }
    }

    private fun adminConnection() = DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username ?: PostgreSQLServer.USERNAME,
        postgres.password ?: PostgreSQLServer.PASSWORD,
    )

    companion object {
        private const val LOCK_KEY = 537001L
        private val postgres = PostgreSQLServer.Launcher.postgres
    }
}
