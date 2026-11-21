package io.bluetape4k.workshop.commerce.metering.eventsourcing.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.metering.contract.ContractHttpResponse
import io.bluetape4k.workshop.commerce.metering.contract.UsageBillingHttpContract
import io.bluetape4k.workshop.commerce.metering.eventsourcing.config.EventSourcingHealthIndicator
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.METERING_EVENT_TABLES
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.MeteringEventsJdbcExecutor
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.worker.ProjectionScheduler
import io.bluetape4k.workshop.commerce.metering.eventsourcing.worker.ProjectionWorker
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.contributor.Status
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
import org.springframework.test.web.reactive.server.returnResult
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Instant
import java.util.*
import javax.sql.DataSource


@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EventSourcingHttpIntegrationTest.TestUsers::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * event-sourced usage billing의 HTTP 계약과 projection consistency 경계를 실제
 * Spring Boot random-port와 PostgreSQL에서 확인합니다.
 *
 * command idempotency, stream/projection position header, wait-for-position, tenant
 * authorization, rebuild/readiness를 고객과 operator 관점에서 검증합니다. 내부
 * table 모양보다 외부 계약과 replay 가능한 event authority를 증명하는 test이며,
 * projection lag이 항상 0이라는 성능 주장은 하지 않습니다.
 */
class EventSourcingHttpIntegrationTest {

    companion object: KLogging() {
/**
 * TENANT_A은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TENANT_A = "tenant-a"
/**
 * PASSWORD은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PASSWORD = "test-secret"
/**
 * HTTP_TIMEOUT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val HTTP_TIMEOUT = 30.seconds()

/**
 * postgres은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

        @JvmStatic
        @DynamicPropertySource
/**
 * postgresProperties은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

    @LocalServerPort
    @Suppress("VarCouldBeVal")
/**
 * port은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var port: Int = 0

    @Autowired
    @Suppress("VarCouldBeVal")
/**
 * dataSource은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var dataSource: DataSource

    @Autowired
    @Suppress("VarCouldBeVal")
/**
 * projectionWorker은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var projectionWorker: ProjectionWorker

    @Autowired
    @Suppress("VarCouldBeVal")
/**
 * projectionScheduler은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var projectionScheduler: ProjectionScheduler

    @Autowired
    @Suppress("VarCouldBeVal")
/**
 * healthIndicator은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var healthIndicator: EventSourcingHealthIndicator

    @Autowired
    @Suppress("VarCouldBeVal")
/**
 * meterRegistry은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    @Suppress("VarCouldBeVal")
/**
 * eventStore은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var eventStore: EventStore

    @Autowired
    @Suppress("VarCouldBeVal")
/**
 * objectMapper은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var objectMapper: ObjectMapper

/**
 * executor은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var executor: MeteringEventsJdbcExecutor
/**
 * client은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val client: WebTestClient by lazy {
/**
 * httpClient은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "live HTTP preserves the shared business contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live HTTP preserves the shared business contract`() {
        UsageBillingHttpContract.verifyRegistrationAndRetry { path, username, idempotencyKey, body ->
            val response = client
                .post()
                .uri(path)
                .headers { headers ->
                    headers.setBasicAuth(username, PASSWORD)
                    idempotencyKey?.let { headers.set("Idempotency-Key", it) }
                }
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .returnResult<String>()

            ContractHttpResponse(
                status = response.status.value(),
                headers = mapOf(
                    "Idempotency-Replayed" to response.responseHeaders.get("Idempotency-Replayed").orEmpty()
                ),
                body = response.responseBody.blockFirst().orEmpty(),
            )
        }
    }

    @Test
/**
 * 이 시나리오는 "command event metadata preserves receipt correlation causation and actor" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `command event metadata preserves receipt correlation causation and actor`() {
        client.post()
            .uri("/api/v1/tenants/$TENANT_A/meters")
            .header("Idempotency-Key", "metadata-meter")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"code":"metadata","unit":"request"}""")
            .exchange()
            .expectStatus().isCreated

        val persisted = executor.transaction {
            eventStore.load(StreamKey(TENANT_A, "Meter", "metadata")).single()
        }
        log.debug { "persisted $persisted" }

        val metadata = objectMapper.readTree(persisted.metadata)
        val commandId = metadata.get("commandId").stringValue()

        commandId.shouldNotBeBlank()
        metadata.get("correlationId").stringValue() shouldBeEqualTo commandId
        metadata.get("causationId").stringValue() shouldBeEqualTo commandId
        metadata.get("actorId").stringValue() shouldBeEqualTo TENANT_A
    }

    @Test
/**
 * 이 시나리오는 "query exposes consistency headers and bounded wait timeout" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `query exposes consistency headers and bounded wait timeout`() {
        executor.transaction { ProjectionGenerationRepository().createInitialActive("billing", 1, Instant.now()) }

        client.get()
            .uri("/api/v1/tenants/$TENANT_A/billing/summary")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Projection-Position", "0")
            .expectHeader().valueEquals("Projection-Lag", "0")

        client.get()
            .uri("/api/v1/tenants/$TENANT_A/billing/summary")
            .header("X-Wait-For-Position", "1")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("projection_not_caught_up")
    }

    @Test
/**
 * 이 시나리오는 "operator routes reject tenant authority and allow operator role" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator routes reject tenant authority and allow operator role`() {
        executor.transaction {
            ProjectionGenerationRepository()
                .createInitialActive("billing", 1, Instant.now())
        }
        val path = "/api/admin/event-sourcing/projections/billing"

        client.get()
            .uri(path)
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange()
            .expectStatus().isForbidden

        client.get()
            .uri(path)
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.state").isEqualTo("ACTIVE")
            .jsonPath("$.lag").isEqualTo(0)
            .jsonPath("$.quarantined").isEqualTo(false)

        client.get()
            .uri("/actuator/metrics")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange()
            .expectStatus().isForbidden

        client.get()
            .uri("/actuator/metrics")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange()
            .expectStatus().isOk

        val reconciliation = "/api/admin/event-sourcing/reconciliation" +
                "?tenantId=$TENANT_A&startsAt=2026-07-01T00:00:00Z&endsAt=2026-08-01T00:00:00Z"

        client.get()
            .uri(reconciliation)
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange()
            .expectStatus().isForbidden

        client.get()
            .uri(reconciliation)
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange().expectStatus().isNoContent
    }

    @Test
/**
 * 이 시나리오는 "operator starts one idempotent rebuild and inspects its generation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator starts one idempotent rebuild and inspects its generation`() {
        executor.transaction {
            ProjectionGenerationRepository().createInitialActive("billing", 1, Instant.now())
        }
        val rebuildMetricBefore = meterRegistry
            .counter("billing.projection.rebuild", "outcome", "started")
            .count()
        val path = "/api/admin/event-sourcing/projections/billing/rebuilds"

        client.post()
            .uri(path)
            .header("Idempotency-Key", "rebuild-billing")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange()
            .expectStatus().isForbidden

        client.post()
            .uri(path)
            .header("Idempotency-Key", "rebuild-billing")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange()
            .expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.generation").isEqualTo(2)
            .jsonPath("$.state").isEqualTo("BUILDING")
            .jsonPath("$.actorId").isEqualTo("operator")

        client.post()
            .uri(path)
            .header("Idempotency-Key", "rebuild-billing")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange()
            .expectStatus().isAccepted
            .expectHeader().valueEquals("Idempotency-Replayed", "true")

        client.post()
            .uri(path)
            .header("Idempotency-Key", "another-rebuild")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("rebuild_already_in_progress")

        client.get()
            .uri("/api/admin/event-sourcing/projections/billing/generations/2")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.generation").isEqualTo(2)
            .jsonPath("$.state").isEqualTo("BUILDING")

        projectionScheduler.runOnce()

        client.get()
            .uri("/api/admin/event-sourcing/projections/billing")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.generation").isEqualTo(2)
            .jsonPath("$.state").isEqualTo("ACTIVE")

        meterRegistry
            .counter("billing.projection.rebuild", "outcome", "started")
            .count() shouldBeEqualTo rebuildMetricBefore + 1
    }

    @Test
/**
 * 이 시나리오는 "worker uses fenced lease and actuator health reports projection state" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `worker uses fenced lease and actuator health reports projection state`() {
        val appendCountBefore = meterRegistry.timer("billing.event.append", "outcome", "success").count()
        val replayCountBefore = meterRegistry.counter("billing.event.replay", "outcome", "success").count()

        healthIndicator.health().status shouldBeEqualTo Status.DOWN
        executor.transaction {
            ProjectionGenerationRepository().createInitialActive("billing", 1, Instant.now())
        }

        client.post()
            .uri("/api/v1/tenants/$TENANT_A/meters")
            .header("Idempotency-Key", "worker-meter")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"code":"api-calls","unit":"request"}""")
            .exchange()
            .expectStatus().isCreated

        client.post()
            .uri("/api/v1/tenants/$TENANT_A/meters")
            .header("Idempotency-Key", "worker-meter-2")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"code":"storage","unit":"byte"}""")
            .exchange()
            .expectStatus().isCreated

        val result = projectionWorker.runOnce(
            projectionName = "billing",
            generation = 1,
            ownerToken = UUID.randomUUID(),
            limit = 1
        )

        log.debug { "result=$result" }
        result.acquired.shouldBeTrue()
        result.applied shouldBeEqualTo 1
        result.checkpoint shouldBeEqualTo 1
        result.lag shouldBeEqualTo 1

        healthIndicator.health().status shouldBeEqualTo Status.UP

        meterRegistry
            .counter("billing.projection.batch", "outcome", "success")
            .count() shouldBeEqualTo 1.0

        meterRegistry
            .timer("billing.event.append", "outcome", "success")
            .count() shouldBeEqualTo appendCountBefore + 2

        meterRegistry
            .counter("billing.event.replay", "outcome", "success")
            .count() shouldBeEqualTo replayCountBefore + 2

        client.get()
            .uri("/api/v1/tenants/$TENANT_A/billing/summary")
            .headers { it.setBasicAuth(TENANT_A, PASSWORD) }
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Projection-Position", "1")
            .expectHeader().valueEquals("Projection-Lag", "1")

        val actuatorHealth = client.get()
            .uri("/actuator/health")
            .exchange()
            .returnResult<String>()

        val actuatorBody = actuatorHealth.responseBody.blockFirst().orEmpty()
        actuatorHealth.status.value() shouldBeEqualTo 200
        actuatorBody shouldNotContain "projectionPosition"

        val operatorHealth = client.get()
            .uri("/actuator/health")
            .headers { it.setBasicAuth("operator", PASSWORD) }
            .exchange()
            .returnResult<String>()

        val operatorHealthBody = operatorHealth.responseBody.blockFirst().orEmpty()
        operatorHealth.status.value() shouldBeEqualTo 200
        operatorHealthBody shouldContain "projectionPosition"
    }

    @TestConfiguration(proxyBeanMethods = false)
/**
 * TestUsers 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    class TestUsers {
        @Bean
/**
 * userDetailsService은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun userDetailsService(): UserDetailsService =
            InMemoryUserDetailsManager(
                User
                    .withUsername(TENANT_A)
                    .password("{noop}$PASSWORD")
                    .authorities("TENANT_BILLING_WRITE", "TENANT_BILLING_READ")
                    .build(),
                User
                    .withUsername("operator")
                    .password("{noop}$PASSWORD")
                    .roles("OPERATOR")
                    .build(),
            )
    }
}
