package io.bluetape4k.workshop.commerce.voucherpool

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolTestKeyMaterialConfiguration
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.http.HttpClient
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("voucher-pool-test")
@Import(VoucherPoolTestKeyMaterialConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Suppress(
    "UnnecessaryAbstractClass",
    "AbstractClassCanBeConcreteClass",
) // Shared JUnit fixture must not be discovered as a concrete test class.
/**
 * AbstractVoucherPoolIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal abstract class AbstractVoucherPoolIntegrationTest {

    protected companion object {
/**
 * HTTP_TIMEOUT은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val HTTP_TIMEOUT: Duration = 60.seconds()
/**
 * postgres은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

        @JvmStatic
        @DynamicPropertySource
/**
 * postgresProperties은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username ?: PostgreSQLServer.USERNAME }
            registry.add("spring.datasource.password") { postgres.password ?: PostgreSQLServer.PASSWORD }
            registry.add("management.server.port") { 0 }
            registry.add("workshop.voucher-pool.worker-dispatcher-enabled") { false }
            registry.add("workshop.voucher-pool.http.operator-secret") { "test-operator-secret-0000000000000001" }
            registry.add("workshop.voucher-pool.http.operator-guard") { "test-voucher-pool-operator-guard" }
        }
    }

    @LocalServerPort
/**
 * port은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    protected var port: Int = 0

/**
 * webTestClient은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    protected val webTestClient: WebTestClient by lazy { testClient(port) }

/**
 * testClient은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun testClient(port: Int): WebTestClient {
        val connector = JdkClientHttpConnector(HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build())
        return WebTestClient.bindToServer(connector)
            .baseUrl("http://127.0.0.1:$port")
            .responseTimeout(HTTP_TIMEOUT)
            .build()
    }

}
