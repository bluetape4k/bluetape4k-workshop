package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.sql.Connection

@Tag("stress")
@EnabledIfSystemProperty(named = "eventSourcedStress", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventSourcedQueryPlanStressTest {
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase =
            EventSourcedPostgresTestDatabase(
                PostgreSQLServer.Launcher.postgres,
                "issue-538-query-plan-stress",
                maximumPoolSize = 4,
            )
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @BeforeEach
    fun createAndSeedSchema() {
        transaction(database) {
            SchemaUtils.drop(ProjectionReadModels, IdempotencyReceipts, EventSnapshots, EventLog)
            SchemaUtils.create(EventLog, EventSnapshots, IdempotencyReceipts, ProjectionReadModels)
        }
        postgresDatabase.dataSource.connection.use { connection ->
            connection.autoCommit = false
            seedEventAuthority(connection)
            seedSnapshots(connection)
            seedReceipts(connection)
            seedProjection(connection)
            connection.commit()
            connection.createStatement().use { statement ->
                statement.execute("ANALYZE voucher_event_log")
                statement.execute("ANALYZE voucher_event_snapshot")
                statement.execute("ANALYZE voucher_idempotency_receipt")
                statement.execute("ANALYZE voucher_projection_read_model")
            }
        }
    }

    @AfterEach
    fun dropSchema() =
        transaction(database) {
            SchemaUtils.drop(ProjectionReadModels, IdempotencyReceipts, EventSnapshots, EventLog)
        }

    @Test
    fun `seeded authority queries retain bounded index plans`() {
        postgresDatabase.dataSource.connection.use { connection ->
            queryShapes().forEach { query ->
                val evidence = connection.explain(query)
                println(evidence.summary())
                evidence.plan shouldContain "Index"
                evidence.plan shouldNotContain "Seq Scan"
                evidence.sharedBuffers shouldBeLessOrEqualTo(MAX_SHARED_BUFFERS)
                evidence.executionMillis shouldBeLessOrEqualTo(MAX_EXECUTION_MILLIS)
            }
        }
    }

    private fun seedEventAuthority(connection: Connection) {
        connection.execute(
            """
            INSERT INTO voucher_event_log (
                event_id, tenant_id, stream_type, stream_id, stream_version, global_position,
                event_type, schema_version, occurred_at, recorded_at, correlation_id,
                causation_id, actor_surrogate, actor_hmac_key_version, payload, canonical_checksum
            )
            SELECT
                md5('event-' || sequence)::uuid,
                'tenant-' || lpad(((sequence - 1) % 100)::text, 2, '0'),
                'campaign',
                md5(((sequence - 1) % 10000)::text)::uuid,
                ((sequence - 1) / 10000) + 1,
                sequence,
                'stress.seeded',
                1,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                sequence::text,
                NULL,
                repeat('a', 64),
                1,
                '{}',
                repeat('b', 64)
            FROM generate_series(1, 100000) AS sequence
            """.trimIndent(),
        )
    }

    private fun seedSnapshots(connection: Connection) {
        connection.execute(
            """
            INSERT INTO voucher_event_snapshot (
                snapshot_id, tenant_id, stream_type, stream_id, stream_version,
                schema_version, key_version, canonical_digest, payload, created_at
            )
            SELECT
                md5('snapshot-' || sequence)::uuid,
                'tenant-' || lpad(((sequence - 1) % 100)::text, 2, '0'),
                'campaign',
                md5(((sequence - 1) % 10000)::text)::uuid,
                10,
                1,
                1,
                repeat('c', 64),
                '{}',
                CURRENT_TIMESTAMP
            FROM generate_series(1, 10000) AS sequence
            """.trimIndent(),
        )
    }

    private fun seedReceipts(connection: Connection) {
        connection.execute(
            """
            INSERT INTO voucher_idempotency_receipt (
                receipt_id, tenant_id, principal_digest, operation, resource_id, key_digest,
                fingerprint, status, owner_token_digest, lease_deadline, command_deadline,
                created_at, updated_at
            )
            SELECT
                md5('receipt-' || sequence)::uuid,
                'tenant-' || lpad(((sequence - 1) % 100)::text, 2, '0'),
                md5('principal-' || sequence),
                'ALLOCATE',
                'campaign-' || ((sequence - 1) % 1000),
                md5('key-' || sequence),
                md5('fingerprint-' || sequence),
                'IN_PROGRESS',
                md5('owner-' || sequence),
                CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                CURRENT_TIMESTAMP - INTERVAL '1 second',
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            FROM generate_series(1, 10000) AS sequence
            """.trimIndent(),
        )
    }

    private fun seedProjection(connection: Connection) {
        connection.execute(
            """
            INSERT INTO voucher_projection_read_model (
                read_model_id, projection, generation, tenant_id, stream_type, stream_id,
                stream_version, global_position, event_type, payload_digest, fencing_token, updated_at
            )
            SELECT
                md5('projection-' || sequence)::uuid,
                'campaign',
                1,
                'tenant-' || lpad(((sequence - 1) % 100)::text, 2, '0'),
                'campaign',
                md5('projection-stream-' || sequence)::uuid,
                1,
                sequence,
                'stress.seeded',
                repeat('d', 64),
                1,
                CURRENT_TIMESTAMP
            FROM generate_series(1, 100000) AS sequence
            """.trimIndent(),
        )
    }

    private fun queryShapes(): List<QueryShape> =
        authorityQueryShapes() + operationalQueryShapes()

    private fun authorityQueryShapes(): List<QueryShape> =
        listOf(
            QueryShape(
                "stream-tail",
                """
                SELECT *
                  FROM voucher_event_log
                 WHERE tenant_id = 'tenant-99'
                   AND stream_type = 'campaign'
                   AND stream_id = md5('9999')::uuid
                 ORDER BY stream_version DESC
                 LIMIT 100
                """.trimIndent(),
            ),
            QueryShape(
                "global-scan",
                """
                SELECT *
                  FROM voucher_event_log
                 WHERE global_position > 99000
                 ORDER BY global_position
                 LIMIT 200
                """.trimIndent(),
            ),
            QueryShape(
                "latest-snapshot",
                """
                SELECT *
                  FROM voucher_event_snapshot
                 WHERE tenant_id = 'tenant-99'
                   AND stream_type = 'campaign'
                   AND stream_id = md5('9999')::uuid
                 ORDER BY stream_version DESC
                 LIMIT 1
                """.trimIndent(),
            ),
        )

    private fun operationalQueryShapes(): List<QueryShape> =
        listOf(
            QueryShape(
                "dedup",
                """
                SELECT *
                  FROM voucher_idempotency_receipt
                 WHERE tenant_id = 'tenant-99'
                   AND principal_digest = md5('principal-10000')
                   AND operation = 'ALLOCATE'
                   AND resource_id = 'campaign-999'
                   AND key_digest = md5('key-10000')
                 LIMIT 1
                """.trimIndent(),
            ),
            QueryShape(
                "retry-scan",
                """
                SELECT *
                  FROM voucher_idempotency_receipt
                 WHERE status = 'IN_PROGRESS'
                   AND command_deadline <= CURRENT_TIMESTAMP
                 ORDER BY command_deadline
                 LIMIT 100
                """.trimIndent(),
            ),
            QueryShape(
                "projection-scan",
                """
                SELECT *
                  FROM voucher_projection_read_model
                 WHERE projection = 'campaign'
                   AND generation = 1
                   AND global_position > 99000
                 ORDER BY global_position
                 LIMIT 200
                """.trimIndent(),
            ),
        )

    private fun Connection.execute(sql: String) {
        createStatement().use { statement -> statement.execute(sql) }
    }

    private companion object {
        private const val MAX_SHARED_BUFFERS = 512
        private const val MAX_EXECUTION_MILLIS = 100.0
    }
}

private data class QueryShape(
    val name: String,
    val sql: String,
)

private data class QueryPlanEvidence(
    val name: String,
    val plan: String,
    val sharedBuffers: Int,
    val executionMillis: Double,
) {
    fun summary(): String =
        "event-sourced-plan name=$name sharedBuffers=$sharedBuffers executionMillis=$executionMillis"
}

private fun Connection.explain(query: QueryShape): QueryPlanEvidence {
    val plan =
        prepareStatement("EXPLAIN (ANALYZE, BUFFERS) ${query.sql}").use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getString(1))
                }.joinToString("\n")
            }
        }
    val sharedBuffers =
        SHARED_BUFFER_PATTERN
            .findAll(plan)
            .map { match -> match.groupValues[1].toInt() }
            .maxOrNull()
            ?: 0
    val executionMillis =
        checkNotNull(EXECUTION_TIME_PATTERN.find(plan)) { "execution time missing for ${query.name}" }
            .groupValues[1]
            .toDouble()
    return QueryPlanEvidence(query.name, plan, sharedBuffers, executionMillis)
}

private val SHARED_BUFFER_PATTERN = Regex("""shared (?:hit|read)=(\d+)""")
private val EXECUTION_TIME_PATTERN = Regex("""Execution Time: ([0-9.]+) ms""")
