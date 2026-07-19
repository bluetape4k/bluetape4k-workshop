package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
internal abstract class AbstractVoucherIntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    protected val webTestClient: WebTestClient by lazy {
        val connector = JdkClientHttpConnector(HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build())
        WebTestClient.bindToServer(connector)
            .baseUrl("http://127.0.0.1:$port")
            .responseTimeout(HTTP_TIMEOUT)
            .build()
    }

    protected fun createActiveCampaign(
        tenant: String,
        campaignId: UUID = Uuid.V7.nextId(),
    ): UUID {
        val body =
            mapOf(
                "campaignId" to campaignId,
                "startsAt" to Instant.now().minusSeconds(60),
                "endsAt" to Instant.now().plusSeconds(3600),
                "capacity" to 10,
                "perUserLimit" to 1,
                "redemptionTtlSeconds" to 600,
            )
        operatorPost(tenant, "/operator/api/v1/campaigns", "create-$campaignId", body) {
            it.header("If-None-Match", "*")
        }.exchange().expectStatus().isCreated
        operatorPost(
            tenant,
            "/operator/api/v1/campaigns/$campaignId/activate",
            "activate-$campaignId",
            mapOf("expectedRevision" to 0),
        ).exchange().expectStatus().isOk
        return campaignId
    }

    protected fun randomIdentifier(): String = Base58.randomString(8)

    protected fun operatorPost(
        tenant: String,
        path: String,
        idempotencyKey: String,
        body: Any,
        customize: (WebTestClient.RequestBodySpec) -> WebTestClient.RequestBodySpec = { it },
    ): WebTestClient.RequestHeadersSpec<*> {
        val request =
            webTestClient.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header(TENANT_HEADER, tenant)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
                .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
                .header("Origin", "http://127.0.0.1:$port")
        return customize(request).bodyValue(body)
    }

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

    protected companion object {
        const val TENANT_HEADER = "X-Workshop-Tenant"
        const val PRINCIPAL_HEADER = "X-Workshop-Principal"
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val OPERATOR_SECRET_HEADER = "X-Workshop-Operator-Secret"
        const val OPERATOR_GUARD_HEADER = "X-Workshop-Guard"
        const val OPERATOR_SECRET = "local-operator-secret-0000000000000001"
        const val OPERATOR_GUARD = "voucher-workshop-operator"
        val HTTP_TIMEOUT: Duration = Duration.ofSeconds(60)
        val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { requireNotNull(postgres.username) }
            registry.add("spring.datasource.password") { requireNotNull(postgres.password) }
            registry.add("workshop.voucher.redis.enabled") { false }
            registry.add("workshop.voucher.http.operator-secret") { OPERATOR_SECRET }
            registry.add("workshop.voucher.http.operator-guard") { OPERATOR_GUARD }
        }
    }
}
