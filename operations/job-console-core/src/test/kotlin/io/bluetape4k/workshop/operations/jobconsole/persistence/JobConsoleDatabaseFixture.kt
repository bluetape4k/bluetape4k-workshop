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

internal class JobConsoleDatabaseFixture: AutoCloseable {

    private val schema = "job_console_${UUID.randomUUID().toString().replace("-", "")}"
    val dataSource: HikariDataSource
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

    fun count(table: String): Long = transaction(database) { tableFor(table).selectAll().count() }

    fun countHistoryToState(state: String): Long = transaction(database) {
        JobHistoryTable.selectAll().where { JobHistoryTable.toState eq state }.count()
    }

    fun countRequestsForTenant(tenantId: String): Long = transaction(database) {
        JobRequestTable.selectAll().where { JobRequestTable.tenantId eq tenantId }.count()
    }

    fun countRequestsByResponseStatus(status: Int): Long = transaction(database) {
        JobRequestTable.selectAll().where { JobRequestTable.responseStatus eq status }.count()
    }

    fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    fun queryLines(sql: String): List<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET enable_seqscan = off")
                statement.executeQuery(sql).use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
        }

    fun queryLinesWithoutPlannerOverride(sql: String): List<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
        }

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

    fun rawSelect(sql: String): Statement<*> = RawSelectStatement(sql)

    override fun close() {
        if (::database.isInitialized) {
            TransactionManager.closeAndUnregister(database)
        }
        dataSource.close()
        adminConnection().use { connection ->
            connection.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    private fun adminConnection() =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )

    companion object {
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
    }

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

private class RawSelectStatement(
    private val sql: String,
): Statement<ResultApi>(StatementType.SELECT, emptyList()) {
    override fun prepareSQL(transaction: Transaction, prepared: Boolean): String = sql

    override fun arguments(): Iterable<Iterable<Pair<IColumnType<*>, Any?>>> = emptyList()
}

private object JobSchemaHistoryTable: Table("job_schema_history") {
    val version = varchar("version", 64)
    val checksum = char("checksum", 64)
    val appliedAt = timestamp("applied_at")

    override val primaryKey = PrimaryKey(version)
}
