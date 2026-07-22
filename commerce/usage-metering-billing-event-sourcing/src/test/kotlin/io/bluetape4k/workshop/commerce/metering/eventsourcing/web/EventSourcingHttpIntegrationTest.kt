package io.bluetape4k.workshop.commerce.metering.eventsourcing.web

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.metering.contract.ContractHttpClient
import io.bluetape4k.workshop.commerce.metering.contract.ContractHttpResponse
import io.bluetape4k.workshop.commerce.metering.contract.UsageBillingHttpContract
import io.bluetape4k.workshop.commerce.metering.eventsourcing.config.EventSourcingHealthIndicator
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.METERING_EVENT_TABLES
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.MeteringEventsJdbcExecutor
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.worker.ProjectionWorker
import io.bluetape4k.workshop.commerce.metering.eventsourcing.worker.ProjectionScheduler
import io.micrometer.core.instrument.MeterRegistry
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
import org.springframework.boot.health.contributor.Status
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EventSourcingHttpIntegrationTest.TestUsers::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventSourcingHttpIntegrationTest {
    @LocalServerPort
    @Suppress("VarCouldBeVal")
    private var port: Int = 0

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var dataSource: DataSource

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var projectionWorker: ProjectionWorker

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var projectionScheduler: ProjectionScheduler

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var healthIndicator: EventSourcingHealthIndicator

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var eventStore: EventStore

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var objectMapper: ObjectMapper

    private lateinit var executor: MeteringEventsJdbcExecutor
    private val client: WebTestClient by lazy {
        val httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build()
        WebTestClient.bindToServer(JdkClientHttpConnector(httpClient))
            .baseUrl("http://127.0.0.1:$port")
            .responseTimeout(HTTP_TIMEOUT)
            .build()
    }

    @BeforeAll
    fun connectExposed() {
        executor = MeteringEventsJdbcExecutor(dataSource)
    }

    @BeforeEach
    @Suppress("DEPRECATION")
    fun resetSchema() {
        executor.transaction {
            SchemaUtils.drop(*METERING_EVENT_TABLES.reversedArray())
            SchemaUtils.createMissingTablesAndColumns(*METERING_EVENT_TABLES)
        }
    }

    @AfterAll
    fun disconnectExposed() = executor.close()

    @Test
    fun `live HTTP preserves the shared business contract`() {
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
                    response.status.value(),
                    mapOf("Idempotency-Replayed" to response.responseHeaders.get("Idempotency-Replayed").orEmpty()),
                    response.responseBody.blockFirst().orEmpty(),
                )
            },
        )
    }

    @Test
    fun `command event metadata preserves receipt correlation causation and actor`() {
        client.post().uri("/api/v1/tenants/$TENANT_A/meters")
            .header("Idempotency-Key", "metadata-meter")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"code":"metadata","unit":"request"}""")
            .exchange()
            .expectStatus().isCreated

        val persisted = executor.transaction {
            eventStore.load(StreamKey(TENANT_A, "Meter", "metadata")).single()
        }
        val metadata = objectMapper.readTree(persisted.metadata)
        val commandId = metadata.get("commandId").stringValue()

        assertTrue(commandId.isNotBlank())
        assertEquals(commandId, metadata.get("correlationId").stringValue())
        assertEquals(commandId, metadata.get("causationId").stringValue())
        assertEquals(TENANT_A, metadata.get("actorId").stringValue())
    }

    @Test
    fun `query exposes consistency headers and bounded wait timeout`() {
        executor.transaction { ProjectionGenerationRepository().createInitialActive("billing", 1, Instant.now()) }

        client.get().uri("/api/v1/tenants/$TENANT_A/billing/summary")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Projection-Position", "0")
            .expectHeader().valueEquals("Projection-Lag", "0")

        client.get().uri("/api/v1/tenants/$TENANT_A/billing/summary")
            .header("X-Wait-For-Position", "1")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("projection_not_caught_up")
    }

    @Test
    fun `operator routes reject tenant authority and allow operator role`() {
        executor.transaction { ProjectionGenerationRepository().createInitialActive("billing", 1, Instant.now()) }
        val path = "/api/admin/event-sourcing/projections/billing"

        client.get().uri(path).headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange().expectStatus().isForbidden
        client.get().uri(path).headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange().expectStatus().isOk
            .expectBody().jsonPath("$.state").isEqualTo("ACTIVE")
            .jsonPath("$.lag").isEqualTo(0)
            .jsonPath("$.quarantined").isEqualTo(false)

        client.get().uri("/actuator/metrics").headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange().expectStatus().isForbidden
        client.get().uri("/actuator/metrics").headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange().expectStatus().isOk

        val reconciliation = "/api/admin/event-sourcing/reconciliation" +
            "?tenantId=$TENANT_A&startsAt=2026-07-01T00:00:00Z&endsAt=2026-08-01T00:00:00Z"
        client.get().uri(reconciliation).headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange().expectStatus().isForbidden
        client.get().uri(reconciliation).headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange().expectStatus().isNoContent
    }

    @Test
    fun `operator starts one idempotent rebuild and inspects its generation`() {
        executor.transaction { ProjectionGenerationRepository().createInitialActive("billing", 1, Instant.now()) }
        val rebuildMetricBefore = meterRegistry.counter("billing.projection.rebuild", "outcome", "started").count()
        val path = "/api/admin/event-sourcing/projections/billing/rebuilds"

        client.post().uri(path)
            .header("Idempotency-Key", "rebuild-billing")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange().expectStatus().isForbidden

        client.post().uri(path)
            .header("Idempotency-Key", "rebuild-billing")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange().expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.generation").isEqualTo(2)
            .jsonPath("$.state").isEqualTo("BUILDING")
            .jsonPath("$.actorId").isEqualTo("operator")

        client.post().uri(path)
            .header("Idempotency-Key", "rebuild-billing")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange().expectStatus().isAccepted
            .expectHeader().valueEquals("Idempotency-Replayed", "true")

        client.post().uri(path)
            .header("Idempotency-Key", "another-rebuild")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("rebuild_already_in_progress")

        client.get().uri("/api/admin/event-sourcing/projections/billing/generations/2")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.generation").isEqualTo(2)
            .jsonPath("$.state").isEqualTo("BUILDING")

        projectionScheduler.runOnce()

        client.get().uri("/api/admin/event-sourcing/projections/billing")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.generation").isEqualTo(2)
            .jsonPath("$.state").isEqualTo("ACTIVE")
        assertEquals(
            rebuildMetricBefore + 1,
            meterRegistry.counter("billing.projection.rebuild", "outcome", "started").count(),
        )
    }

    @Test
    fun `worker uses fenced lease and actuator health reports projection state`() {
        val appendCountBefore = meterRegistry.timer("billing.event.append", "outcome", "success").count()
        val replayCountBefore = meterRegistry.counter("billing.event.replay", "outcome", "success").count()
        assertEquals(Status.DOWN, healthIndicator.health().status)
        executor.transaction { ProjectionGenerationRepository().createInitialActive("billing", 1, Instant.now()) }

        client.post().uri("/api/v1/tenants/$TENANT_A/meters")
            .header("Idempotency-Key", "worker-meter")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"code":"api-calls","unit":"request"}""")
            .exchange()
            .expectStatus().isCreated

        client.post().uri("/api/v1/tenants/$TENANT_A/meters")
            .header("Idempotency-Key", "worker-meter-2")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"code":"storage","unit":"byte"}""")
            .exchange()
            .expectStatus().isCreated

        val result = projectionWorker.runOnce("billing", 1, UUID.randomUUID(), limit = 1)

        assertTrue(result.acquired)
        assertEquals(1, result.applied)
        assertEquals(1, result.checkpoint)
        assertEquals(1, result.lag)
        assertEquals(Status.UP, healthIndicator.health().status)
        assertEquals(1.0, meterRegistry.counter("billing.projection.batch", "outcome", "success").count())
        assertEquals(appendCountBefore + 2, meterRegistry.timer("billing.event.append", "outcome", "success").count())
        assertEquals(replayCountBefore + 2, meterRegistry.counter("billing.event.replay", "outcome", "success").count())

        client.get().uri("/api/v1/tenants/$TENANT_A/billing/summary")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Projection-Position", "1")
            .expectHeader().valueEquals("Projection-Lag", "1")

        val actuatorHealth = client.get().uri("/actuator/health")
            .exchange()
            .returnResult(String::class.java)
        val actuatorBody = actuatorHealth.responseBody.blockFirst().orEmpty()
        assertEquals(200, actuatorHealth.status.value(), actuatorBody)
        assertFalse(actuatorBody.contains("projectionPosition"))

        val operatorHealth = client.get().uri("/actuator/health")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange()
            .returnResult(String::class.java)
        val operatorHealthBody = operatorHealth.responseBody.blockFirst().orEmpty()
        assertEquals(200, operatorHealth.status.value(), operatorHealthBody)
        assertTrue(operatorHealthBody.contains("projectionPosition"), operatorHealthBody)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestUsers {
        @Bean
        fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager(
            User.withUsername(TENANT_A).password("{noop}$PASSWORD")
                .authorities("TENANT_BILLING_WRITE", "TENANT_BILLING_READ")
                .build(),
            User.withUsername("operator").password("{noop}$PASSWORD").roles("OPERATOR").build(),
        )
    }

    companion object {
        private const val TENANT_A = "tenant-a"
        private const val PASSWORD = "test-secret"
        private val HTTP_TIMEOUT = Duration.ofSeconds(30)
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username ?: PostgreSQLServer.USERNAME }
            registry.add("spring.datasource.password") { postgres.password ?: PostgreSQLServer.PASSWORD }
            registry.add("management.endpoint.health.show-details") { "when-authorized" }
            registry.add("management.endpoint.health.roles") { "OPERATOR" }
            registry.add("workshop.metering-events.projection.initial-delay") { "1h" }
            registry.add("workshop.metering-events.projection.fixed-delay") { "1h" }
        }
    }
}
