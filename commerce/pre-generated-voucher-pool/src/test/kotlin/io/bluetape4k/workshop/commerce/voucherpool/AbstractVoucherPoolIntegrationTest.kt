package io.bluetape4k.workshop.commerce.voucherpool

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.http.HttpClient
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Suppress("UnnecessaryAbstractClass") // Shared JUnit fixture must not be discovered as a concrete test class.
internal abstract class AbstractVoucherPoolIntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    protected val webTestClient: WebTestClient by lazy { testClient(port) }

    protected fun testClient(port: Int): WebTestClient {
        val connector = JdkClientHttpConnector(HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build())
        return WebTestClient.bindToServer(connector)
            .baseUrl("http://127.0.0.1:$port")
            .responseTimeout(HTTP_TIMEOUT)
            .build()
    }

    protected companion object {
        val HTTP_TIMEOUT: Duration = Duration.ofSeconds(60)
        val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username ?: PostgreSQLServer.USERNAME }
            registry.add("spring.datasource.password") { postgres.password ?: PostgreSQLServer.PASSWORD }
            registry.add("management.server.port") { 0 }
        }
    }
}
