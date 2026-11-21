package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedOperationsConfiguration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.awaitility.Awaitility.await
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.explain
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
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
import java.util.UUID

@Tag("integration")
@Suppress("SqlResolve")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * EventStorePostgresCapabilityIntegrationTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventStorePostgresCapabilityIntegrationTest {

    companion object: KLogging() {
/**
 * ADVISORY_LOCK_KEY은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val ADVISORY_LOCK_KEY = 538L
/**
 * TENANT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TENANT = "tenant-capability"
/**
 * STREAM_TYPE은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val STREAM_TYPE = "campaign"
/**
 * STREAM_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val STREAM_ID = "01984f10-a21e-7c0b-8e12-9d6d62f05462"
/**
 * SEEDED_EVENT_COUNT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val SEEDED_EVENT_COUNT = 512
/**
 * LOCK_TIMEOUT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val LOCK_TIMEOUT: Duration = 5.seconds()
    }

/**
 * postgresDatabase은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
/**
 * database은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(
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
/**
 * 이 시나리오는 "transaction advisory lock excludes a second owner and releases on commit" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "stream tail query uses a stream version index" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

            log.debug { "plan: $plan" }
            plan shouldContain "Index Scan"
            plan shouldContain indexName
            connection.isClosed.shouldBeFalse()
            connection.autoCommit.shouldBeFalse()
            connection.rollback()
            withExistingJdbcTransaction(connection) {
                EventLog.selectAll().count()
            } shouldBeEqualTo 0L
        }
    }

    @Test
/**
 * 이 시나리오는 "startup probe fails while the application role cannot connect and recovers after login
 * is restored" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * createProbeRole은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * probeDataSource은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * verifyDatabaseRecovery은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun verifyDatabaseRecovery(
        applicationDataSource: HikariDataSource,
        role: String,
    ) {
/**
 * applicationDatabase은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val applicationDatabase = Database.connect(applicationDataSource)
/**
 * probe은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val probe = EventSourcedOperationsConfiguration()
            .eventSourcedStartupProbe(
                EventSourcedExposedDatabaseRegistration(applicationDatabase),
                EventSourcedDatabasePermitGate(),
            )
        probe.verify()

        executeAsAdmin("ALTER ROLE $role NOLOGIN")
        applicationDataSource.hikariPoolMXBean.softEvictConnections()

        assertFailsWith<ExposedSQLException> {
            probe.verify()
        }

        executeAsAdmin("ALTER ROLE $role LOGIN")
        await atMost LOCK_TIMEOUT untilAsserted { probe.verify() }
    }

/**
 * dropProbeRole은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun dropProbeRole(role: String) {
        executeAsAdmin(
            "DROP OWNED BY $role",
            "DROP ROLE IF EXISTS $role",
        )
    }

/**
 * executeAsAdmin은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun executeAsAdmin(vararg sql: String) {
        postgresDatabase.dataSource.connection.use { admin ->
            admin.createStatement().use { statement ->
                sql.forEach(statement::execute)
            }
        }
    }

/**
 * tryTransactionAdvisoryLock은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun Connection.tryTransactionAdvisoryLock(key: Long): Boolean =
        prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, key)
            statement.executeQuery().use { result ->
                result.next().shouldBeTrue()
                result.getBoolean(1)
            }
        }

/**
 * seedEventStream은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * streamVersionIndexName은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * streamTailPlan은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun Connection.streamTailPlan(): String = withExistingJdbcTransaction(this) {
/**
 * query은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val query = EventLog
            .selectAll()
            .where {
                (EventLog.tenantId eq TENANT) and
                    (EventLog.streamType eq STREAM_TYPE) and
                    (EventLog.streamId eq UUID.fromString(STREAM_ID)) and
                    (EventLog.streamVersion greater 400L)
            }
            .orderBy(EventLog.streamVersion to SortOrder.ASC)
            .limit(100)

        TransactionManager.current()
            .explain(options = "FORMAT TEXT") { query }
            .toList()
            .joinToString("\n") { it.toString().substringAfter('=') }
    }

}
