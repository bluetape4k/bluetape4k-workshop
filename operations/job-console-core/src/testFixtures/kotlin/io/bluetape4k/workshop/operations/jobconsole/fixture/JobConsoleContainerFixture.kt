package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.storage.RedisServer
import java.util.UUID

data class JobConsoleContainerFixture(
    val jdbcUrl: String,
    val databaseUsername: String,
    val databasePassword: String,
    val redisUri: String,
    val schema: String,
) {
    companion object {
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
