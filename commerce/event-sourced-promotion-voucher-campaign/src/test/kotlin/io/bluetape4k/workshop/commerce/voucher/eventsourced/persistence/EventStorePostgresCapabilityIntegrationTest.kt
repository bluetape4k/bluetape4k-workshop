package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedOperationsConfiguration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.awaitility.Awaitility.await
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.time.Duration

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventStorePostgresCapabilityIntegrationTest {
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase =
            EventSourcedPostgresTestDatabase(
                postgres = PostgreSQLServer.Launcher.postgres,
                poolName = "issue-538-postgres-capabilities",
            )
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @BeforeEach
    fun createSchema() =
        transaction(database) {
            SchemaUtils.drop(EventLog)
            SchemaUtils.create(EventLog)
        }

    @AfterEach
    fun dropSchema() = transaction(database) { SchemaUtils.drop(EventLog) }

    @Test
    fun `transaction advisory lock excludes a second owner and releases on commit`() {
        postgresDatabase.dataSource.connection.use { first ->
            postgresDatabase.dataSource.connection.use { second ->
                first.autoCommit = false
                second.autoCommit = false

                first.tryTransactionAdvisoryLock(ADVISORY_LOCK_KEY).shouldBeTrue()
                second.tryTransactionAdvisoryLock(ADVISORY_LOCK_KEY).shouldBeFalse()

                first.commit()
                await().atMost(LOCK_TIMEOUT).untilAsserted {
                    second.tryTransactionAdvisoryLock(ADVISORY_LOCK_KEY).shouldBeTrue()
                }
                second.commit()
            }
        }
    }

    @Test
    fun `stream tail query uses a stream version index`() {
        postgresDatabase.dataSource.connection.use { connection ->
            connection.autoCommit = false
            seedEventStream(connection)
            connection.createStatement().use { statement ->
                statement.execute("ANALYZE voucher_event_log")
                statement.execute("SET LOCAL enable_seqscan = off")
            }

            val indexName = connection.streamVersionIndexName()
            val plan = connection.streamTailPlan()

            plan shouldContain "Index Scan"
            plan shouldContain indexName
            connection.rollback()
        }
    }

    @Test
    fun `startup probe fails while the application role cannot connect and recovers after login is restored`() {
        val role = "voucher_probe_${Base58.randomString(12).lowercase()}"
        val password = "voucher-probe-password"
        createProbeRole(role, password)

        try {
            probeDataSource(role, password).use { applicationDataSource ->
                verifyDatabaseRecovery(applicationDataSource, role)
            }
        } finally {
            dropProbeRole(role)
        }
    }

    private fun createProbeRole(
        role: String,
        password: String,
    ) {
        executeAsAdmin(
            "CREATE ROLE $role LOGIN PASSWORD '$password'",
            "GRANT USAGE ON SCHEMA public TO $role",
            "GRANT SELECT ON voucher_event_log TO $role",
        )
    }

    private fun probeDataSource(
        role: String,
        password: String,
    ): HikariDataSource =
        PostgreSQLServer.Launcher.postgres.getHikariDataSource {
            poolName = "issue-538-probe-role"
            username = role
            this.password = password
            maximumPoolSize = 1
            minimumIdle = 0
            connectionTimeout = 1_000
        }

    private fun verifyDatabaseRecovery(
        applicationDataSource: HikariDataSource,
        role: String,
    ) {
        val applicationDatabase = Database.connect(applicationDataSource)
        val probe =
            EventSourcedOperationsConfiguration()
                .eventSourcedStartupProbe(
                    EventSourcedExposedDatabaseRegistration(applicationDatabase),
                    EventSourcedDatabasePermitGate(),
                )
        probe.verify()

        executeAsAdmin("ALTER ROLE $role NOLOGIN")
        applicationDataSource.hikariPoolMXBean.softEvictConnections()
        assertFailsWith<ExposedSQLException> { probe.verify() }

        executeAsAdmin("ALTER ROLE $role LOGIN")
        await().atMost(LOCK_TIMEOUT).untilAsserted { probe.verify() }
    }

    private fun dropProbeRole(role: String) {
        executeAsAdmin(
            "DROP OWNED BY $role",
            "DROP ROLE IF EXISTS $role",
        )
    }

    private fun executeAsAdmin(vararg sql: String) {
        postgresDatabase.dataSource.connection.use { admin ->
            admin.createStatement().use { statement ->
                sql.forEach(statement::execute)
            }
        }
    }

    private fun Connection.tryTransactionAdvisoryLock(key: Long): Boolean =
        prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, key)
            statement.executeQuery().use { result ->
                result.next().shouldBeTrue()
                result.getBoolean(1)
            }
        }

    private fun seedEventStream(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO voucher_event_log (
                event_id, tenant_id, stream_type, stream_id, stream_version, global_position,
                event_type, schema_version, occurred_at, recorded_at, correlation_id,
                causation_id, actor_surrogate, actor_hmac_key_version, payload, canonical_checksum
            )
            SELECT
                md5(sequence::text)::uuid, ?, ?, ?::uuid, sequence, sequence,
                'campaign.created', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, sequence::text,
                NULL, repeat('a', 64), 1, '{}', repeat('b', 64)
            FROM generate_series(1, ?) AS sequence
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, TENANT)
            statement.setString(2, STREAM_TYPE)
            statement.setString(3, STREAM_ID)
            statement.setInt(4, SEEDED_EVENT_COUNT)
            statement.executeUpdate()
        }
    }

    private fun Connection.streamVersionIndexName(): String =
        prepareStatement(
            """
            SELECT indexname
            FROM pg_indexes
            WHERE schemaname = current_schema()
              AND tablename = 'voucher_event_log'
              AND indexdef LIKE '%(tenant_id, stream_type, stream_id, stream_version)%'
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                result.next().shouldBeTrue()
                result.getString(1)
            }
        }

    private fun Connection.streamTailPlan(): String =
        prepareStatement(
            """
            EXPLAIN (FORMAT TEXT)
            SELECT *
            FROM voucher_event_log
            WHERE tenant_id = ?
              AND stream_type = ?
              AND stream_id = ?::uuid
              AND stream_version > ?
            ORDER BY stream_version ASC
            LIMIT 100
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, TENANT)
            statement.setString(2, STREAM_TYPE)
            statement.setString(3, STREAM_ID)
            statement.setLong(4, 400L)
            statement.executeQuery().use { result ->
                buildString {
                    while (result.next()) {
                        appendLine(result.getString(1))
                    }
                }
            }
        }

    companion object {
        private const val ADVISORY_LOCK_KEY = 538L
        private const val TENANT = "tenant-capability"
        private const val STREAM_TYPE = "campaign"
        private const val STREAM_ID = "01984f10-a21e-7c0b-8e12-9d6d62f05462"
        private const val SEEDED_EVENT_COUNT = 512
        private val LOCK_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
