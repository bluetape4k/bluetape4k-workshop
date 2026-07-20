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
            migrations = listOf(JobMigration.classpath("001", "db/job-console/V001__job_console.sql")),
            advisoryLockKey = 520001L,
        ).migrate().single()

    fun count(table: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
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
