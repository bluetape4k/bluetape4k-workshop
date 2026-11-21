package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
/**
 * AbstractVoucherIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal abstract class AbstractVoucherIntegrationTest {

    protected companion object: KLogging() {
/**
 * TENANT_HEADER은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TENANT_HEADER = "X-Workshop-Tenant"
/**
 * PRINCIPAL_HEADER은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PRINCIPAL_HEADER = "X-Workshop-Principal"
/**
 * IDEMPOTENCY_HEADER은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
/**
 * OPERATOR_SECRET_HEADER은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val OPERATOR_SECRET_HEADER = "X-Workshop-Operator-Secret"
/**
 * OPERATOR_GUARD_HEADER은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val OPERATOR_GUARD_HEADER = "X-Workshop-Guard"
/**
 * OPERATOR_SECRET은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val OPERATOR_SECRET = "local-operator-secret-0000000000000001"
/**
 * OPERATOR_GUARD은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val OPERATOR_GUARD = "voucher-workshop-operator"
/**
 * HTTP_TIMEOUT은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val HTTP_TIMEOUT: Duration = 60.seconds()
/**
 * postgres은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres
/**
 * applicationJdbcUrl은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val applicationJdbcUrl: String = VoucherTestSchema.create("voucher_web")

        @JvmStatic
        @DynamicPropertySource
/**
 * postgresProperties은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { applicationJdbcUrl }
            registry.add("spring.datasource.username") { requireNotNull(postgres.username) }
            registry.add("spring.datasource.password") { requireNotNull(postgres.password) }
            registry.add("workshop.voucher.redis.enabled") { false }
            registry.add("management.server.port") { 0 }
            registry.add("workshop.voucher.http.operator-secret") { OPERATOR_SECRET }
            registry.add("workshop.voucher.http.operator-guard") { OPERATOR_GUARD }
        }
    }

    @LocalServerPort
/**
 * port은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    protected var port: Int = 0

/**
 * webTestClient은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    protected val webTestClient: WebTestClient by lazy { testClient(port) }

/**
 * testClient은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun testClient(port: Int): WebTestClient {
        val connector = JdkClientHttpConnector(
            HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build()
        )

        return WebTestClient.bindToServer(connector)
            .baseUrl("http://127.0.0.1:$port")
            .responseTimeout(HTTP_TIMEOUT)
            .build()
    }

/**
 * createActiveCampaign은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun createActiveCampaign(
        tenant: String,
        campaignId: UUID = Uuid.V7.nextId(),
    ): UUID {
/**
 * body은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val body = mapOf(
                "campaignId" to campaignId,
                "startsAt" to Instant.now().minusSeconds(60),
                "endsAt" to Instant.now().plusSeconds(3600),
                "capacity" to 10,
                "perUserLimit" to 1,
                "redemptionTtlSeconds" to 600,
            )
        operatorPost(
            tenant = tenant,
            path = "/operator/api/v1/campaigns",
            idempotencyKey = "create-$campaignId",
            body = body
        ) {
            it.header("If-None-Match", "*")
        }
            .exchange()
            .expectStatus().isCreated

        operatorPost(
            tenant = tenant,
            path = "/operator/api/v1/campaigns/$campaignId/activate",
            idempotencyKey = "activate-$campaignId",
            body = mapOf("expectedRevision" to 0),
        )
            .exchange()
            .expectStatus().isOk
        
        return campaignId
    }

/**
 * randomIdentifier은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun randomIdentifier(): String = Base58.randomString(8)

/**
 * operatorPost은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun operatorPost(
        tenant: String,
        path: String,
        idempotencyKey: String,
        body: Any,
        customize: (WebTestClient.RequestBodySpec) -> WebTestClient.RequestBodySpec = { it },
    ): WebTestClient.RequestHeadersSpec<*> {
/**
 * request은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val request = webTestClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header(TENANT_HEADER, tenant)
            .header(IDEMPOTENCY_HEADER, idempotencyKey)
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .header("Origin", "http://127.0.0.1:$port")

        return customize(request).bodyValue(body)
    }

/**
 * operatorGet은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun operatorGet(
        tenant: String,
        path: String,
    ): WebTestClient.RequestHeadersSpec<*> =
        webTestClient.get()
            .uri(path)
            .header(TENANT_HEADER, tenant)
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .header("Origin", "http://127.0.0.1:$port")

/**
 * customerPost은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    protected fun customerPost(
        tenant: String,
        principal: String,
        path: String,
        idempotencyKey: String,
        body: Any,
    ): WebTestClient.RequestHeadersSpec<*> =
        webTestClient.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header(TENANT_HEADER, tenant)
            .header(PRINCIPAL_HEADER, principal)
            .header(IDEMPOTENCY_HEADER, idempotencyKey)
            .bodyValue(body)
}
