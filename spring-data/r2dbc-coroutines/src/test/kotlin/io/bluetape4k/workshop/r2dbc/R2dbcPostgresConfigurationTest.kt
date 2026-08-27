package io.bluetape4k.workshop.r2dbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.r2dbc.spi.ConnectionFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest
class R2dbcPostgresConfigurationTest {

    @Autowired
    private lateinit var connectionFactory: ConnectionFactory

    @Test
    fun `spring r2dbc properties select the PostgreSQLServer fixture`() {
        connectionFactory.metadata.name shouldBeEqualTo "PostgreSQL"
    }

    companion object {
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgres.host}:${postgres.getMappedPort(PostgreSQLServer.PORT)}/${postgres.databaseName}"
            }
            registry.add("spring.r2dbc.username") { checkNotNull(postgres.username) }
            registry.add("spring.r2dbc.password") { checkNotNull(postgres.password) }
            registry.add("spring.sql.init.mode") { "always" }
        }
    }
}
