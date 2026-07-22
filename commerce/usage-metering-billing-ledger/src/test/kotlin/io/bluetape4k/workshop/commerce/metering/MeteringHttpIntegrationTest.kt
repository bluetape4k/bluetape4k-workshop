package io.bluetape4k.workshop.commerce.metering

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.metering.persistence.BillingCalendarEntity
import io.bluetape4k.workshop.commerce.metering.persistence.METERING_TABLES
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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
    private var port: Int = 0

    private val client: WebTestClient by lazy {
        val httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build()
        WebTestClient.bindToServer(JdkClientHttpConnector(httpClient))
            .baseUrl("http://127.0.0.1:$port")
            .responseTimeout(HTTP_TIMEOUT)
            .build()
    }

    @BeforeEach
    @Suppress("DEPRECATION") // A disposable PostgreSQL schema keeps the HTTP boundary test self-contained.
    fun resetSchema() {
        transaction {
            SchemaUtils.drop(*METERING_TABLES.reversedArray())
            SchemaUtils.createMissingTablesAndColumns(*METERING_TABLES)
            BillingCalendarEntity.new(Uuid.V7.nextId()) {
                tenantId = TENANT_A
                currency = "USD"
                createdAt = Instant.now()
            }
        }
    }

    @Test
    fun `live HTTP preserves replay conflict and tenant isolation contracts`() {
        registerMeter(TENANT_A, "meter-request", "request")
            .exchange()
            .expectStatus().isCreated

        registerMeter(TENANT_A, "meter-request", "request")
            .exchange()
            .expectStatus().isCreated
            .expectHeader().valueEquals("Idempotency-Replayed", "true")

        registerMeter(TENANT_A, "meter-request", "call")
            .exchange()
            .expectStatus().isEqualTo(409)

        registerMeter("tenant-b", "cross-tenant", "request")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("tenant_mismatch")
    }

    private fun registerMeter(
        tenantId: String,
        idempotencyKey: String,
        unit: String,
    ): WebTestClient.RequestHeadersSpec<*> =
        client.post()
            .uri("/api/v1/tenants/$tenantId/meters")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"code":"api_calls","unit":"$unit"}""")

    @TestConfiguration(proxyBeanMethods = false)
    class TestUsers {
        @Bean
        fun testUsers(): UserDetailsService =
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
