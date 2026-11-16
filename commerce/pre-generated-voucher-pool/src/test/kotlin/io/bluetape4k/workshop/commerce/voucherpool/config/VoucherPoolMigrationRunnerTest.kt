@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.jdbc.sql.singleLong
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.core.io.AbstractResource
import org.springframework.core.io.ClassPathResource
import java.io.IOException
import java.lang.reflect.Proxy
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolMigrationRunnerTest {

    companion object: KLogging() {
        private const val LOCK_KEY = 537001L
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

    private lateinit var voucherSchema: String
    private lateinit var dataSource: HikariDataSource

    @BeforeEach
    fun createSchema() {
        voucherSchema = "voucher_pool_${Base58.randomString(8).lowercase()}"
        adminConnection().use { it.createStatement().execute("CREATE SCHEMA $voucherSchema") }
        dataSource = postgres.getHikariDataSource {
            this.schema = voucherSchema
        }
    }

    @AfterEach
    fun dropSchema() {
        adminConnection().use { it.createStatement().execute("DROP SCHEMA IF EXISTS $voucherSchema CASCADE") }
        dataSource.close()
    }

    @Test
    fun `production migration is complete idempotent and checksum guarded`() {
        val runner = runner()
        runner.migrate() shouldBeEqualTo VoucherPoolMigrationResult.APPLIED
        runner.migrate() shouldBeEqualTo VoucherPoolMigrationResult.ALREADY_APPLIED

        queryLong(
            """
                SELECT count(*) FROM information_schema.tables 
                WHERE table_schema='$voucherSchema' 
                  AND table_name LIKE 'voucher_pool_%'
                """.trimIndent(),
        ) shouldBeEqualTo 18L

        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE voucher_pool_schema_history SET checksum='drift' WHERE version='001'")
                .executeUpdate()
        }
        assertFailsWith<VoucherPoolMigrationException> {
            runner.migrate()
        }.code shouldBeEqualTo VoucherPoolMigrationFailureCode.CHECKSUM_DRIFT
    }

    @Test
    fun `production migration exposes complete authority metadata`() {
        runner().migrate()

        queryLong(
            """
                SELECT count(*) FROM information_schema.columns 
                 WHERE table_schema='$voucherSchema' 
                   AND ((table_name='voucher_pool_entries' AND column_name IN ('code_nonce','wrap_nonce')) 
                       OR (table_name='voucher_pool_audits' AND column_name='request_digest') 
                       OR (table_name='voucher_pool_worker_claims' 
                           AND column_name IN ('attempt','next_attempt_at','checkpoint','poison_reason')
                          )
                       )
               """.trimIndent(),
        ) shouldBeEqualTo 7L

        queryLong(
            """
                SELECT count(*) FROM pg_indexes 
                 WHERE schemaname='$voucherSchema' 
                   AND indexname='uq_voucher_pool_reservation_active_entry' 
                   AND indexdef LIKE '%WHERE%state%ACTIVE%'
                """.trimIndent(),
        ) shouldBeEqualTo 1L
        queryLong(
            """
                SELECT count(*) 
                  FROM pg_constraint c 
                  JOIN pg_class t ON t.oid=c.conrelid
                  JOIN pg_namespace n ON n.oid=t.relnamespace 
                 WHERE n.nspname='$voucherSchema'
                   AND c.contype='f' AND t.relname IN ('voucher_pool_entries','voucher_pool_reservations','voucher_pool_allocations','voucher_pool_audits')
               """.trimIndent(),
        ) shouldBeEqualTo 12L
    }

    @Test
    fun `held advisory startup lock times out without schema mutation`() {
        adminConnection().use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { stmt ->
                stmt.executeQuery("SELECT pg_advisory_xact_lock($LOCK_KEY)").use {
                    it.next()
                }
            }

            assertFailsWith<VoucherPoolMigrationException> {
                runner(30.millis()).migrate()
            }.code shouldBeEqualTo VoucherPoolMigrationFailureCode.LOCK_TIMEOUT

            blocker.rollback()
        }
        queryLong("SELECT count(*) FROM information_schema.tables WHERE table_schema='$voucherSchema'") shouldBeEqualTo 0L
    }

    @Test
    fun `resource and connection acquisition failures remain classified`() {
        val unavailableResource = object: AbstractResource() {
            override fun getDescription(): String = "unavailable migration"

            override fun getInputStream() = throw IOException("unavailable")
        }
        assertFailsWith<VoucherPoolMigrationException> {
            VoucherPoolMigrationRunner(
                dataSource = dataSource,
                migration = VoucherPoolMigration("unavailable", unavailableResource),
                advisoryLockKey = LOCK_KEY,
            ).migrate()
        }.code shouldBeEqualTo VoucherPoolMigrationFailureCode.RESOURCE_UNAVAILABLE

        assertFailsWith<VoucherPoolMigrationException> {
            VoucherPoolMigrationRunner(
                dataSource = unavailableDataSource(),
                migration = VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
                advisoryLockKey = LOCK_KEY,
            ).migrate()
        }.code shouldBeEqualTo VoucherPoolMigrationFailureCode.CONNECTION_UNAVAILABLE
    }

    private fun runner(timeout: Duration = 2.seconds()) =
        VoucherPoolMigrationRunner(
            dataSource = dataSource,
            migration = VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            advisoryLockKey = LOCK_KEY,
            lockTimeout = timeout,
        )

    private fun queryLong(sql: String): Long =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { result ->
                    result.singleLong()
                }
            }
        }

    private fun adminConnection() = DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username ?: PostgreSQLServer.USERNAME,
        postgres.password ?: PostgreSQLServer.PASSWORD,
    )

    private fun unavailableDataSource(): DataSource = Proxy.newProxyInstance(
        DataSource::class.java.classLoader,
        arrayOf(DataSource::class.java),
    ) { _, method, _ ->
        if (method.name == "getConnection") throw SQLException("unavailable")
        error("unexpected DataSource method: ${method.name}")
    } as DataSource

}
