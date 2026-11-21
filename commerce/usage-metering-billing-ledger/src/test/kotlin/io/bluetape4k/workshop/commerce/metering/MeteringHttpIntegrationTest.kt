package io.bluetape4k.workshop.commerce.metering

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
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
import org.springframework.test.web.reactive.server.returnResult
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
/**
 * MeteringHttpIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class MeteringHttpIntegrationTest {

    companion object: KLogging() {
/**
 * HTTP_TIMEOUT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val HTTP_TIMEOUT: Duration = 30.seconds()
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
        }
    }

    @LocalServerPort
    @Suppress("VarCouldBeVal") // Spring injects the random port after construction.
/**
 * port은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var port: Int = 0

    @Autowired
    @Suppress("VarCouldBeVal") // Spring injects the managed datasource after construction.
/**
 * dataSource은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var dataSource: DataSource

/**
 * executor은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var executor: MeteringJdbcExecutor

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
/**
 * 이 시나리오는 "live HTTP preserves replay conflict and tenant isolation contracts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live HTTP preserves replay conflict and tenant isolation contracts`() {
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
                    "Idempotency-Replayed" to response.responseHeaders.get("Idempotency-Replayed").orEmpty(),
                ),
                body = response.responseBody.blockFirst().orEmpty(),
            )
        }
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
                User.withUsername(TENANT_A).password("{noop}$PASSWORD").roles("TENANT").build(),
                User.withUsername("operator").password("{noop}$PASSWORD").roles("OPERATOR").build(),
            )
    }
}
