package io.bluetape4k.workshop.commerce.order

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal abstract class AbstractOrderLifecycleIntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(30))
            .build()
    }

    companion object {
        val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username ?: PostgreSQLServer.USERNAME }
            registry.add("spring.datasource.password") { postgres.password ?: PostgreSQLServer.PASSWORD }
        }
    }
}
