package io.bluetape4k.workshop.leader.jobsafety.support

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.leader.jobsafety.persistence.JOB_SAFETY_TABLES
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyJdbcExecutor
import io.lettuce.core.api.StatefulRedisConnection
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal abstract class AbstractJobSafetyIntegrationTest {
    @Autowired
    protected lateinit var jdbc: JobSafetyJdbcExecutor

    @Autowired
    private lateinit var redisConnection: StatefulRedisConnection<String, String>

    @BeforeEach
    fun resetBackends() {
        redisConnection.sync().flushdb()
        jdbc.transaction {
            withExposed {
                SchemaUtils.drop(*JOB_SAFETY_TABLES.reversedArray())
                SchemaUtils.createMissingTablesAndColumns(*JOB_SAFETY_TABLES)
            }
        }
    }

    companion object {
        private val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres
        private val redis: RedisServer = RedisServer.Launcher.redis

        @JvmStatic
        @DynamicPropertySource
        fun backendProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username ?: PostgreSQLServer.USERNAME }
            registry.add("spring.datasource.password") { postgres.password ?: PostgreSQLServer.PASSWORD }
            registry.add("spring.datasource.hikari.minimum-idle") { "0" }
            registry.add("workshop.job-safety.redis.uri") { redis.url }
        }
    }
}
