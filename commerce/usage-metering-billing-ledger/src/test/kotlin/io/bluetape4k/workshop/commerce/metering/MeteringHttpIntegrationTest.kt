package io.bluetape4k.workshop.commerce.metering

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.metering.contract.ContractHttpClient
import io.bluetape4k.workshop.commerce.metering.contract.ContractHttpResponse
import io.bluetape4k.workshop.commerce.metering.contract.UsageBillingHttpContract
import io.bluetape4k.workshop.commerce.metering.persistence.BillingCalendarEntity
import io.bluetape4k.workshop.commerce.metering.persistence.METERING_TABLES
import io.bluetape4k.workshop.commerce.metering.persistence.MeteringJdbcExecutor
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

@Tag("integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["workshop.metering.close.scheduler-enabled=false"],
)
@Import(MeteringHttpIntegrationTest.TestUsers::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeteringHttpIntegrationTest {
    @LocalServerPort
    @Suppress("VarCouldBeVal") // Spring injects the random port after construction.
    private var port: Int = 0

    @Autowired
    @Suppress("VarCouldBeVal") // Spring injects the managed datasource after construction.
    private lateinit var dataSource: DataSource

    private lateinit var executor: MeteringJdbcExecutor

    private val client: WebTestClient by lazy {
        val httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build()
        WebTestClient.bindToServer(JdkClientHttpConnector(httpClient))
            .baseUrl("http://127.0.0.1:$port")
            .responseTimeout(HTTP_TIMEOUT)
            .build()
    }

    @BeforeAll
    fun connectExposed() {
        executor = MeteringJdbcExecutor(dataSource)
    }

    @BeforeEach
    @Suppress("DEPRECATION") // A disposable PostgreSQL schema keeps the HTTP boundary test self-contained.
    fun resetSchema() {
        executor.transaction {
            withExposed {
                SchemaUtils.drop(*METERING_TABLES.reversedArray())
                SchemaUtils.createMissingTablesAndColumns(*METERING_TABLES)
                BillingCalendarEntity.new(Uuid.V7.nextId()) {
                    tenantId = TENANT_A
                    currency = "USD"
                    createdAt = Instant.now()
                }
            }
        }
    }

    @AfterAll
    fun disconnectExposed() {
        executor.close()
    }

    @Test
    fun `live HTTP preserves replay conflict and tenant isolation contracts`() {
        UsageBillingHttpContract.verifyRegistrationAndRetry(
            ContractHttpClient { path, username, idempotencyKey, body ->
                val response = client.post()
                    .uri(path)
                    .headers { headers ->
                        headers.setBasicAuth(username, PASSWORD)
                        idempotencyKey?.let { headers.set("Idempotency-Key", it) }
                    }
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .returnResult(String::class.java)
                ContractHttpResponse(
                    status = response.status.value(),
                    headers = mapOf(
                        "Idempotency-Replayed" to response.responseHeaders.get("Idempotency-Replayed").orEmpty(),
                    ),
                    body = response.responseBody.blockFirst().orEmpty(),
                )
            },
        )
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestUsers {
        @Bean
        fun userDetailsService(): UserDetailsService =
            InMemoryUserDetailsManager(
                User.withUsername(TENANT_A).password("{noop}$PASSWORD").roles("TENANT").build(),
                User.withUsername("operator").password("{noop}$PASSWORD").roles("OPERATOR").build(),
            )
    }

    companion object {
        private val HTTP_TIMEOUT: Duration = Duration.ofSeconds(30)
        private const val TENANT_A = "tenant-a"
        private const val PASSWORD = "test-secret"
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username ?: PostgreSQLServer.USERNAME }
            registry.add("spring.datasource.password") { postgres.password ?: PostgreSQLServer.PASSWORD }
        }
    }
}
