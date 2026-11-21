package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.javatimes.days
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.shared.voucher.NormalizedVoucherLifecycleResult
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherAllocationBlackBoxRequest
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherAllocationBlackBoxScenario
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherCampaignActivationRequest
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherCampaignBlackBoxContract
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherCampaignBlackBoxRequest
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherLifecycleAction
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherLifecycleBlackBoxScenario
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherLifecycleFailureKind
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRebuildRepository
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["voucher.projection.worker.enabled=false"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * EventSourcedVoucherCompatibilityIntegrationTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedVoucherCompatibilityIntegrationTest @Autowired constructor(
    registration: EventSourcedExposedDatabaseRegistration,
    dataSource: DataSource,
    @LocalServerPort port: Int,
) {

    companion object: KLogging() {
/**
 * POSTGRES은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val POSTGRES by lazy { PostgreSQLServer.Launcher.postgres }
/**
 * HTTP_TIMEOUT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val HTTP_TIMEOUT: Duration = 60.seconds()
/**
 * NOW은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-23T00:00:00Z")
/**
 * PROJECTION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROJECTION = "voucher-lifecycle"
/**
 * OPERATOR_SECRET은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val OPERATOR_SECRET = "workshop-operator-secret"
/**
 * OPERATOR_GUARD은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val OPERATOR_GUARD = "workshop-operator-guard"

        @JvmStatic
        @DynamicPropertySource
/**
 * postgresProperties은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { POSTGRES.jdbcUrl }
            registry.add("spring.datasource.username") { POSTGRES.username.shouldNotBeNull() }
            registry.add("spring.datasource.password") { POSTGRES.password.shouldNotBeNull() }
            registry.add("management.datadog.metrics.export.enabled") { "false" }
        }
    }

/**
 * database은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val database = registration.database
/**
 * origin은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val origin = "http://127.0.0.1:$port"
/**
 * client은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val client: WebTestClient

    init {
        check(dataSource is HikariDataSource) {
            "voucher compatibility integration must use Spring's Hikari DataSource"
        }
        client = testClient()
    }

    @BeforeEach
    fun createSchema() {
        transaction(database) {
            SchemaUtils.create(*EVENT_SOURCED_HTTP_TABLES)
            ProjectionRebuildRepository().initializeActive(PROJECTION, NOW)
        }
    }

    @AfterEach
    fun dropSchema() {
        transaction(database) { SchemaUtils.drop(*EVENT_SOURCED_HTTP_TABLES) }
    }

    @Test
/**
 * 이 시나리오는 "event sourced adapter satisfies the shared normalized campaign contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event sourced adapter satisfies the shared normalized campaign contract`() {
        VoucherCampaignBlackBoxContract.scenarios.forEach { scenario ->
            client
                .postCampaignContract(scenario.first, operatorAccess())
                .assertNormalizedCampaign(scenario.expectedFirst, scenario.first.campaignId)

            scenario.replay?.let { replay ->
                client
                    .postCampaignContract(replay, operatorAccess())
                    .assertNormalizedCampaign(scenario.expectedReplay.shouldNotBeNull(), replay.campaignId)
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "event sourced adapter satisfies the shared campaign activation contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event sourced adapter satisfies the shared campaign activation contract`() {
        val scenario = VoucherCampaignBlackBoxContract.activateAndReplay

        client
            .postCampaignContract(scenario.create, operatorAccess())
            .assertNormalizedCampaign(scenario.expectedCreate, scenario.create.campaignId)

        client
            .postCampaignActivationContract(scenario.first, operatorAccess())
            .assertNormalizedCampaign(scenario.expectedFirst, scenario.first.campaignId)

        client
            .postCampaignActivationContract(scenario.replay, operatorAccess())
            .assertNormalizedCampaign(scenario.expectedReplay, scenario.replay.campaignId)
    }

    @Test
/**
 * 이 시나리오는 "event sourced adapter satisfies the shared voucher allocation contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event sourced adapter satisfies the shared voucher allocation contract`() {
        val scenario = VoucherCampaignBlackBoxContract.allocateAndReplay

        client
            .postCampaignContract(scenario.campaign.create, operatorAccess())
            .assertNormalizedCampaign(scenario.campaign.expectedCreate, scenario.campaign.create.campaignId)

        client
            .postCampaignActivationContract(scenario.campaign.first, operatorAccess())
            .assertNormalizedCampaign(scenario.campaign.expectedFirst, scenario.campaign.first.campaignId)

        client
            .postVoucherAllocationContract(scenario.first)
            .assertNormalizedAllocation(scenario.expectedFirst)

        client
            .postVoucherAllocationContract(scenario.replay)
            .assertNormalizedAllocation(scenario.expectedReplay)
    }

    @Test
/**
 * 이 시나리오는 "event sourced adapter satisfies the shared voucher lifecycle contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event sourced adapter satisfies the shared voucher lifecycle contract`() {
        VoucherCampaignBlackBoxContract.lifecycleScenarios.forEach { scenario ->
            val allocation = createAllocation(scenario)

            postLifecycle(scenario, allocation).assertNormalizedLifecycle(scenario.expectedFirst)
            postLifecycle(scenario, allocation).assertNormalizedLifecycle(scenario.expectedReplay)
        }
    }

    @Test
/**
 * 이 시나리오는 "allocation replay preserves the terminal outcome while rendering current voucher state"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `allocation replay preserves the terminal outcome while rendering current voucher state`() {
        val scenario = VoucherCampaignBlackBoxContract.lifecycleScenarios.first()
        val allocation = createAllocation(scenario)

        postLifecycle(scenario, allocation).assertNormalizedLifecycle(scenario.expectedFirst)

        client
            .postVoucherAllocationContract(scenario.allocation.replay)
            .exchange()
            .expectStatus().isCreated
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody()
            .jsonPath("$.claimId").isEqualTo(allocation.claimId.toString())
            .jsonPath("$.state").isEqualTo("REDEEMED")
            .jsonPath("$.revision").isEqualTo(1)
    }

    @Test
/**
 * 이 시나리오는 "event sourced adapter satisfies the shared allocation failure contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event sourced adapter satisfies the shared allocation failure contract`() {
        VoucherCampaignBlackBoxContract.allocationFailures.forEach { scenario ->
            scenario.campaign?.let { campaign ->
                client
                    .postCampaignContract(campaign.create, operatorAccess())
                    .assertNormalizedCampaign(campaign.expectedCreate, campaign.create.campaignId)

                if (scenario.activateCampaign) {
                    client
                        .postCampaignActivationContract(campaign.first, operatorAccess())
                        .assertNormalizedCampaign(campaign.expectedFirst, campaign.first.campaignId)
                }
            }
            scenario.warmupRequests.forEach { request ->
                client
                    .postVoucherAllocationContract(request)
                    .exchange()
                    .expectStatus().isCreated
            }
            client
                .postVoucherAllocationContract(scenario.failureRequest)
                .assertNormalizedAllocation(scenario.expectedFailure)
        }
    }

    @Test
/**
 * 이 시나리오는 "concurrent final capacity allocation has one authoritative winner" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `concurrent final capacity allocation has one authoritative winner`() {
        val create = createCapacityRaceCampaign()
        val campaignId = create.campaignId

        val barrier = CyclicBarrier(2)
        val responses = ConcurrentLinkedQueue<Pair<Int, String>>()

        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .add {
                val worker = barrier.await(5.seconds)
                val principal = "capacity-customer-$worker"
                val response = testClient()
                    .postVoucherAllocationContract(
                        VoucherAllocationBlackBoxRequest(
                            tenant = create.tenant,
                            principal = principal,
                            idempotencyKey = "capacity-allocate-00$worker",
                            campaignId = campaignId,
                            userRef = principal,
                        ),
                    )
                    .exchange()
                    .expectBody()
                    .returnResult()

                responses.add(
                    response.status.value() to response.responseBodyContent.shouldNotBeNull().toUtf8String(),
                )
            }.run()

        responses.map(Pair<Int, String>::first).sorted() shouldBeEqualTo listOf(201, 409)

        val rejectedBody = responses.single { it.first == 409 }.second
        ("CONCURRENT_MODIFICATION" in rejectedBody || "CAPACITY_EXHAUSTED" in rejectedBody).shouldBeTrue()

        transaction(database) {
            EventLog
                .selectAll()
                .where {
                    (EventLog.streamId eq campaignId) and
                            (EventLog.eventType eq "campaign.voucher-capacity-reserved")
                }.count() shouldBeEqualTo 1L
        }
    }

/**
 * createCapacityRaceCampaign은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createCapacityRaceCampaign(): VoucherCampaignBlackBoxRequest {
        val now = Instant.now()
        val create = VoucherCampaignBlackBoxRequest(
            tenant = "capacity-race",
            principal = "capacity-operator",
            idempotencyKey = "capacity-create-001",
            campaignId = UUID.randomUUID(),
            startsAt = now - 1.minutes(),
            endsAt = now + 2.days(),
            capacity = 1,
            perUserLimit = 1,
            redemptionTtlSeconds = 3_600,
        )

        val activate = VoucherCampaignActivationRequest(
            tenant = create.tenant,
            principal = create.principal,
            idempotencyKey = "capacity-activate-001",
            campaignId = create.campaignId,
            expectedRevision = 0,
        )
        client.postCampaignContract(create, operatorAccess()).exchange().expectStatus().isCreated
        client.postCampaignActivationContract(activate, operatorAccess()).exchange().expectStatus().isOk
        return create
    }

    @Test
/**
 * 이 시나리오는 "event sourced adapter satisfies the shared voucher lifecycle failure contract" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event sourced adapter satisfies the shared voucher lifecycle failure contract`() {
        VoucherCampaignBlackBoxContract.lifecycleFailures.forEach { scenario ->
            val allocation = createAllocation(scenario.allocation)
            val request = scenario.allocation.first
            val principal =
                if (scenario.kind == VoucherLifecycleFailureKind.OTHER_PRINCIPAL) {
                    "${request.principal}-other"
                } else {
                    request.principal
                }
            val code =
                if (scenario.kind == VoucherLifecycleFailureKind.WRONG_CODE) {
                    "V1-invalid-code"
                } else {
                    allocation.code.shouldNotBeNull()
                }
            val expectedRevision = if (scenario.kind == VoucherLifecycleFailureKind.STALE_REVISION) 1 else 0

            client
                .post()
                .uri("/api/v1/claims/${allocation.claimId}/redeem")
                .contentType(MediaType.APPLICATION_JSON)
                .header(TENANT_HEADER, request.tenant)
                .header(PRINCIPAL_HEADER, principal)
                .header(IDEMPOTENCY_HEADER, scenario.idempotencyKey)
                .bodyValue(
                    mapOf(
                        "code" to code,
                        "expectedRevision" to expectedRevision,
                        "redemptionReference" to "contract-failure-order",
                    ),
                ).assertNormalizedLifecycle(scenario.expectedFailure)
        }
    }

/**
 * createAllocation은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createAllocation(
        scenario: VoucherLifecycleBlackBoxScenario,
    ): VoucherAllocationHttpResponse = createAllocation(scenario.allocation)

/**
 * createAllocation은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createAllocation(
        allocation: VoucherAllocationBlackBoxScenario,
    ): VoucherAllocationHttpResponse {
/**
 * campaign은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val campaign = allocation.campaign

        client
            .postCampaignContract(campaign.create, operatorAccess())
            .assertNormalizedCampaign(campaign.expectedCreate, campaign.create.campaignId)

        client
            .postCampaignActivationContract(campaign.first, operatorAccess())
            .assertNormalizedCampaign(campaign.expectedFirst, campaign.first.campaignId)

        return client
            .postVoucherAllocationContract(allocation.first)
            .exchange()
            .expectStatus().isCreated
            .expectBody<VoucherAllocationHttpResponse>()
            .returnResult().responseBody.shouldNotBeNull()
    }

/**
 * postLifecycle은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun postLifecycle(
        scenario: VoucherLifecycleBlackBoxScenario,
        allocation: VoucherAllocationHttpResponse,
    ): WebTestClient.RequestHeadersSpec<*> {
/**
 * request은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val request = scenario.allocation.first
/**
 * path은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val path = when (scenario.action) {
            VoucherLifecycleAction.REDEEM  -> "/api/v1/claims/${allocation.claimId}/redeem"
            VoucherLifecycleAction.RELEASE -> "/api/v1/claims/${allocation.claimId}/release"
        }

/**
 * body은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val body = when (scenario.action) {
            VoucherLifecycleAction.REDEEM  ->
                mapOf(
                    "code" to allocation.code.shouldNotBeNull(),
                    "expectedRevision" to 0,
                    "redemptionReference" to scenario.redemptionReference.shouldNotBeNull(),
                )
            VoucherLifecycleAction.RELEASE -> mapOf("expectedRevision" to 0)
        }

        return client
            .post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header(TENANT_HEADER, request.tenant)
            .header(PRINCIPAL_HEADER, request.principal)
            .header(IDEMPOTENCY_HEADER, scenario.transitionIdempotencyKey)
            .bodyValue(body)
    }

/**
 * testClient은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun testClient(): WebTestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build()

        return WebTestClient
            .bindToServer(JdkClientHttpConnector(httpClient))
            .baseUrl(origin)
            .responseTimeout(HTTP_TIMEOUT)
            .build()
    }

    private fun WebTestClient.RequestHeadersSpec<*>.assertNormalizedLifecycle(
        expected: NormalizedVoucherLifecycleResult,
    ) {
/**
 * response은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = exchange().expectStatus().isEqualTo(expected.status)
        expected.replayed?.let {
            response.expectHeader().valueEquals("Idempotency-Replayed", it.toString())
        }

/**
 * body은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val body = response.expectBody()
        expected.code?.let { body.jsonPath("$.code").isEqualTo(it) }
        expected.state?.let { body.jsonPath("$.state").isEqualTo(it) }
        expected.revision?.let { body.jsonPath("$.revision").isEqualTo(it) }
        expected.policyVersion?.let { body.jsonPath("$.policyVersion").isEqualTo(it) }
    }

/**
 * operatorAccess은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun operatorAccess(): OperatorContractAccess =
        OperatorContractAccess(origin, OPERATOR_SECRET, OPERATOR_GUARD)

}
