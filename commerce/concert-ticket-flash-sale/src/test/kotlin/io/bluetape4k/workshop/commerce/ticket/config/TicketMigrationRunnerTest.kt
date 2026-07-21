package io.bluetape4k.workshop.commerce.ticket.config

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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class TicketMigrationRunnerTest {
    private lateinit var schema: String
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun createIsolatedSchema() {
        schema = "ticket_migration_${Base58.randomString(8).lowercase()}"
        adminConnection().use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
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
                            lockTimeout = Duration.ofSeconds(2),
                        ).migrate()
                    }
                TimeUnit.MILLISECONDS.sleep(50)
                migration.isDone shouldBeEqualTo false
                blocker.rollback()

                migration.get(2, TimeUnit.SECONDS) shouldBeEqualTo TicketMigrationResult.APPLIED
            }
        }
    }

    private fun runner(
        resource: String,
        lockTimeout: Duration = Duration.ofSeconds(1),
    ): TicketMigrationRunner =
        TicketMigrationRunner(
            dataSource = dataSource,
            migration = TicketMigration("001", ClassPathResource(resource)),
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

    private fun adminConnection() =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )

    companion object {
        private const val MIGRATION_LOCK_KEY = 521_001L
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }
}
