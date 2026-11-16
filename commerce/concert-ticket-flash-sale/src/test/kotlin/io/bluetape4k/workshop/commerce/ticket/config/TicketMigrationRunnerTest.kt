package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.jdbc.sql.singleLong
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.core.io.ClassPathResource
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class TicketMigrationRunnerTest {

    companion object {
        private const val MIGRATION_LOCK_KEY = 521_001L
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }

    private lateinit var schema: String
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun createIsolatedSchema() {
        schema = "ticket_migration_${Base58.randomString(8).lowercase()}"

        adminConnection().use { connection ->
            connection.createStatement().use {
                it.execute("CREATE SCHEMA $schema")
            }
        }
        dataSource = postgres.getDataSource {
            this.schema = this@TicketMigrationRunnerTest.schema
        }
    }

    @AfterEach
    fun dropIsolatedSchema() {
        adminConnection().use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    @Test
    fun `migration applies once and matching checksum replays`() {
        val runner = runner("db/migration/test/V001__ticket_probe.sql")

        runner.migrate() shouldBeEqualTo TicketMigrationResult.APPLIED
        runner.migrate() shouldBeEqualTo TicketMigrationResult.ALREADY_APPLIED
        queryLong("SELECT count(*) FROM ticket_schema_history") shouldBeEqualTo 1L
        queryLong("SELECT count(*) FROM ticket_migration_probe") shouldBeEqualTo 0L
    }

    @Test
    fun `production migration creates the complete authority schema and replays`() {
        val runner = runner("db/migration/V001__concert_ticket_flash_sale.sql")

        runner.migrate() shouldBeEqualTo TicketMigrationResult.APPLIED
        runner.migrate() shouldBeEqualTo TicketMigrationResult.ALREADY_APPLIED
        queryLong(
            """
            SELECT count(*)
            FROM information_schema.tables
            WHERE table_schema = '$schema'
              AND table_name LIKE 'ticket_%'
            """.trimIndent(),
        ) shouldBeEqualTo 18L
    }

    @Test
    fun `Spring startup gate runs production migration before readiness opens`() {
        val configuration = TicketConfiguration()
        val readiness = configuration.ticketMigrationReadiness()
        val runner = configuration.ticketMigrationRunner(dataSource, readiness)

        readiness.isReady.shouldBeFalse()
        configuration.ticketSchemaMigration(runner).afterSingletonsInstantiated()

        readiness.isReady.shouldBeTrue()
        queryLong("SELECT count(*) FROM ticket_schema_history") shouldBeEqualTo 1L
    }

    @Test
    fun `checksum drift fails closed`() {
        runner("db/migration/test/V001__ticket_probe.sql").migrate()

        val failure =
            assertFailsWith<TicketMigrationException> {
                runner("db/migration/test/V001__ticket_probe_drift.sql").migrate()
            }

        failure.code shouldBeEqualTo TicketMigrationFailure.CHECKSUM_MISMATCH
        queryLong("SELECT count(*) FROM ticket_schema_history") shouldBeEqualTo 1L
    }

    @Test
    fun `concurrent startup waits for the advisory lock winner`() {
        val readiness = TicketMigrationReadiness()
        adminConnection().use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_advisory_xact_lock($MIGRATION_LOCK_KEY)").use { it.next() }
            }
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val migration =
                    executor.submit<TicketMigrationResult> {
                        runner(
                            resource = "db/migration/test/V001__ticket_probe.sql",
                            lockTimeout = 2.seconds(),
                            readiness = readiness,
                        ).migrate()
                    }
                TimeUnit.MILLISECONDS.sleep(50)
                migration.isDone.shouldBeFalse()
                readiness.isReady.shouldBeFalse()
                blocker.rollback()

                migration.get(2.seconds) shouldBeEqualTo TicketMigrationResult.APPLIED
                readiness.isReady.shouldBeTrue()
            }
        }
    }

    private fun runner(
        resource: String,
        lockTimeout: Duration = 1.seconds(),
        readiness: TicketMigrationReadiness = TicketMigrationReadiness(),
    ): TicketMigrationRunner =
        TicketMigrationRunner(
            dataSource = dataSource,
            migration = TicketMigration("001", ClassPathResource(resource)),
            advisoryLockKey = MIGRATION_LOCK_KEY,
            lockTimeout = lockTimeout,
            readiness = readiness,
        )

    private fun queryLong(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    result.singleLong()
                }
            }
        }

    private fun adminConnection() =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )
}
