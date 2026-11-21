package io.bluetape4k.workshop.operations.jobconsole.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.statements.api.ResultApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.explain
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.javatime.timestamp
import java.sql.DriverManager
import java.util.*

/**
 * JobConsoleDatabaseFixture 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class JobConsoleDatabaseFixture: AutoCloseable {

/**
 * schema은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val schema = "job_console_${UUID.randomUUID().toString().replace("-", "")}"
/**
 * dataSource은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val dataSource: HikariDataSource
/**
 * database은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var database: Database

    init {
        adminConnection().use { connection ->
            connection.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") }
        }
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username ?: PostgreSQLServer.USERNAME
                    password = postgres.password ?: PostgreSQLServer.PASSWORD
                    this.schema = this@JobConsoleDatabaseFixture.schema
                    maximumPoolSize = 8
                },
            )
        database = Database.connect(dataSource)
    }

/**
 * migrate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun migrate(): JobMigrationResult =
        JobMigrationRunner(
            dataSource = dataSource,
            migrations =
                listOf(
                    JobMigration.classpath("001", "db/job-console/V001__job_console.sql"),
                    JobMigration.classpath("002", "db/job-console/V002__bounded_wait_http_idempotency.sql"),
                ),
            advisoryLockKey = 520001L,
        ).migrate().last()

/**
 * count은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun count(table: String): Long = transaction(database) { tableFor(table).selectAll().count() }

/**
 * countHistoryToState은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun countHistoryToState(state: String): Long = transaction(database) {
        JobHistoryTable.selectAll().where { JobHistoryTable.toState eq state }.count()
    }

/**
 * countRequestsForTenant은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun countRequestsForTenant(tenantId: String): Long = transaction(database) {
        JobRequestTable.selectAll().where { JobRequestTable.tenantId eq tenantId }.count()
    }

/**
 * countRequestsByResponseStatus은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun countRequestsByResponseStatus(status: Int): Long = transaction(database) {
        JobRequestTable.selectAll().where { JobRequestTable.responseStatus eq status }.count()
    }

/**
 * execute은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

/**
 * queryLines은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun queryLines(sql: String): List<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET enable_seqscan = off")
                statement.executeQuery(sql).use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
        }

/**
 * queryLinesWithoutPlannerOverride은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun queryLinesWithoutPlannerOverride(sql: String): List<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
        }

/**
 * explainPlan은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun explainPlan(
        analyze: Boolean = false,
        options: String? = null,
        disableSeqScan: Boolean = true,
        query: StatementBuilder.() -> Statement<*>,
    ): List<String> = transaction(database) {
        if (disableSeqScan) exec("SET LOCAL enable_seqscan = off")
        explain(analyze = analyze, options = options, body = query)
            .toList()
            .map { it.toString() }
    }

/**
 * rawSelect은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun rawSelect(sql: String): Statement<*> = RawSelectStatement(sql)

/**
 * close은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun close() {
        if (::database.isInitialized) {
            TransactionManager.closeAndUnregister(database)
        }
        dataSource.close()
        adminConnection().use { connection ->
            connection.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

/**
 * adminConnection은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun adminConnection() =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )

    companion object {
/**
 * postgres은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }

/**
 * tableFor은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun tableFor(name: String): Table = when (name) {
        "job_schema_history" -> JobSchemaHistoryTable
        "job_requests" -> JobRequestTable
        "job_request_waiters" -> JobRequestWaiterTable
        "jobs" -> JobTable
        "job_checkpoints" -> JobCheckpointTable
        "job_attempts" -> JobAttemptTable
        "job_outbox" -> JobOutboxTable
        "job_history" -> JobHistoryTable
        "job_duration_samples" -> JobDurationSampleTable
        else -> error("Unsupported job console fixture table[$name]")
    }
}

/**
 * RawSelectStatement 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class RawSelectStatement(
/**
 * sql은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val sql: String,
): Statement<ResultApi>(StatementType.SELECT, emptyList()) {
/**
 * prepareSQL은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun prepareSQL(transaction: Transaction, prepared: Boolean): String = sql

/**
 * arguments은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun arguments(): Iterable<Iterable<Pair<IColumnType<*>, Any?>>> = emptyList()
}

/**
 * JobSchemaHistoryTable 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private object JobSchemaHistoryTable: Table("job_schema_history") {
/**
 * version은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val version = varchar("version", 64)
/**
 * checksum은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val checksum = char("checksum", 64)
/**
 * appliedAt은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val appliedAt = timestamp("applied_at")

/**
 * primaryKey은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    override val primaryKey = PrimaryKey(version)
}
