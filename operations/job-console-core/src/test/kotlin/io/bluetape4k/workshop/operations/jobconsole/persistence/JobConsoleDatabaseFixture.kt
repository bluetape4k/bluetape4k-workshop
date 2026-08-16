package io.bluetape4k.workshop.operations.jobconsole.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import java.sql.DriverManager
import java.util.UUID

internal class JobConsoleDatabaseFixture : AutoCloseable {
    private val schema = "job_console_${UUID.randomUUID().toString().replace("-", "")}"
    val dataSource: HikariDataSource

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

    fun count(table: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    fun countWhere(table: String, predicate: String): Long = countQuery("SELECT count(*) FROM $table WHERE $predicate")

    private fun countQuery(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result -> result.next(); result.getLong(1) }
            }
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

    override fun close() {
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
}
