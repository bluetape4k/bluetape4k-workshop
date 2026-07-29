package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionJournal
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleDockerResources
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleProxiedTopology
import java.sql.DriverManager
import java.util.UUID

data class JobConsoleContainerFixture(
    val jdbcUrl: String,
    val databaseUsername: String,
    val databasePassword: String,
    val redisUri: String,
    val schema: String,
) {
    fun createSchema() {
        DriverManager.getConnection(jdbcUrl, databaseUsername, databasePassword).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
    }

    fun dropSchema() {
        DriverManager.getConnection(jdbcUrl, databaseUsername, databasePassword).use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    companion object {
        fun proxiedRedis(
            journal: HighContentionJournal,
            resources: JobConsoleDockerResources,
        ): JobConsoleProxiedTopology =
            JobConsoleProxiedTopology.start(journal, resources)

        fun shared(): JobConsoleContainerFixture {
            val postgres = PostgreSQLServer.Launcher.postgres
            val redis = RedisServer.Launcher.redis
            return JobConsoleContainerFixture(
                jdbcUrl = postgres.jdbcUrl,
                databaseUsername = postgres.username ?: PostgreSQLServer.USERNAME,
                databasePassword = postgres.password ?: PostgreSQLServer.PASSWORD,
                redisUri = "redis://${redis.host}:${redis.port}",
                schema = "job_console_${UUID.randomUUID().toString().replace("-", "")}",
            )
        }
    }
}
