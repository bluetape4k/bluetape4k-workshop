package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedRuntimeWorkers
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.OperatorAuditAction
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExposedEventStoreTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.IdempotencyReceipts
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.OperatorAudits
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.EventSourcedProjectionRuntime
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ExposedProjectionEventReader
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionLeaseRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRebuildRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentityMappings
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentityService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
import java.net.Socket
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.sql.DataSource

@Tag("integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["voucher.projection.worker.enabled=false"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * EventSourcedCampaignHttpIntegrationTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedCampaignHttpIntegrationTest @Autowired constructor(
/**
 * registration은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val registration: EventSourcedExposedDatabaseRegistration,
    dataSource: DataSource,
/**
 * clock은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val clock: Clock,
/**
 * sseProperties은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val sseProperties: EventSourcedSseProperties,
/**
 * identities은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val identities: SubjectIdentityService,
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
 * SSE_EVENT_TIMEOUT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val SSE_EVENT_TIMEOUT: Duration = 5.seconds()
/**
 * TENANT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TENANT = "tenant-a"
/**
 * PROJECTION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROJECTION = "voucher-lifecycle"
/**
 * GENERATION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val GENERATION = 1L
/**
 * EVENT_CREATED_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val EVENT_CREATED_ID = "0198a1b2-c3d4-7e5f-8123-456789abc301"
/**
 * EVENT_ACTIVATED_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val EVENT_ACTIVATED_ID = "0198a1b2-c3d4-7e5f-8123-456789abc302"
/**
 * POISON_EVENT_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val POISON_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc303"
/**
 * POISON_READY_AGE_SECONDS은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val POISON_READY_AGE_SECONDS = 20L
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
/**
 * CAMPAIGN_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CAMPAIGN_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc300")
/**
 * CREATE_CAMPAIGN_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CREATE_CAMPAIGN_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc399")
/**
 * SECOND_CREATE_CAMPAIGN_ID은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val SECOND_CREATE_CAMPAIGN_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc398")
/**
 * NOW은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-23T00:00:00Z")

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
            registry.add("voucher.sse.heartbeat-interval") { "100ms" }
        }
    }

/**
 * database은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val database get() = registration.database
/**
 * client은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val client: WebTestClient
/**
 * operatorOrigin은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val operatorOrigin: String
/**
 * serverPort은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val serverPort: Int = port

    init {
        check(dataSource is HikariDataSource) { "live HTTP integration must use Spring's Hikari DataSource" }
        operatorOrigin = "http://127.0.0.1:$port"
/**
 * httpClient은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build()

        client = WebTestClient
            .bindToServer(JdkClientHttpConnector(httpClient))
            .baseUrl("http://127.0.0.1:$port")
            .responseTimeout(HTTP_TIMEOUT)
            .build()
    }

    @BeforeEach
    fun createProjection() {
        transaction(database) {
            SchemaUtils.create(*EVENT_SOURCED_HTTP_TABLES)
            ProjectionRebuildRepository().initializeActive(PROJECTION, NOW)
        }
        val eventStore = EventStoreRepository(ExposedEventStoreTransactionRunner(database))
        transaction(database) {
            eventStore.appendAll(
                listOf(
                    ExpectedAppend(
                        stream = StreamKey(TenantId(TENANT), "campaign", CAMPAIGN_ID),
                        expectedVersion = 0,
                        events = listOf(
                            event(
                                eventId = EVENT_CREATED_ID,
                                eventType = "campaign.created",
                                payload = """
                                          {
                                            "startsAt": "2026-07-24T00:00:00Z",
                                            "endsAt": "2026-07-31T00:00:00Z",
                                            "capacity": 100,
                                            "perUserLimit": 2,
                                            "redemptionTtlSeconds": 3600
                                          }
                                          """.trimIndent(),
                            ),
                            event(EVENT_ACTIVATED_ID, "campaign.activated", "{}"),
                        ),
                    ),
                ),
            )
        }
        transaction(database) {
            val leases = ProjectionLeaseRepository()
            val lease = leases.acquire(PROJECTION, GENERATION, "http-test", NOW).shouldNotBeNull()
            val batch = ExposedProjectionEventReader().loadAfter(0)
            ProjectionRepository(leases)
                .applyBatch(
                    key = ProjectionKey(projection = PROJECTION, generation = GENERATION),
                    lease = lease,
                    events = batch.events,
                    now = NOW
                )
        }
    }

    @AfterEach
    fun dropProjection() {
        transaction(database) {
            SchemaUtils.drop(*EVENT_SOURCED_HTTP_TABLES)
        }
    }

    @Test
/**
 * 이 시나리오는 "live campaign GET preserves the compatible body and adds projection headers" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live campaign GET preserves the compatible body and adds projection headers`() {
        getCampaign(minimumPosition = 2)
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(STREAM_POSITION_HEADER, "2")
            .expectHeader().valueEquals(PROJECTION_POSITION_HEADER, "2")
            .expectHeader().valueEquals(PROJECTION_LAG_HEADER, "0")
            .expectBody()
            .jsonPath("$.campaignId").isEqualTo(CAMPAIGN_ID.toString())
            .jsonPath("$.state").isEqualTo("ACTIVE")
            .jsonPath("$.revision").isEqualTo(1)
            .jsonPath("$.policyVersion").isEqualTo(0)
            .jsonPath("$.capacity").isEqualTo(100)
            .jsonPath("$.allocatedCount").isEqualTo(0)
            .jsonPath("$.remainingCapacity").isEqualTo(100)
    }

    @Test
/**
 * 이 시나리오는 "live campaign GET bounds read your writes wait and returns retry metadata" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live campaign GET bounds read your writes wait and returns retry metadata`() {
        getCampaign(minimumPosition = 3)
            .exchange()
            .expectStatus().isAccepted
            .expectHeader().valueEquals("Retry-After", "1")
            .expectHeader().valueEquals(STREAM_POSITION_HEADER, "2")
            .expectHeader().valueEquals(PROJECTION_POSITION_HEADER, "2")
            .expectHeader().valueEquals(PROJECTION_LAG_HEADER, "0")
            .expectBody()
            .jsonPath("$.code").isEqualTo("PROJECTION_PENDING")
            .jsonPath("$.projectionPosition").isEqualTo(2)
    }

    @Test
/**
 * 이 시나리오는 "live campaign create commits once and replays the original receipt" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live campaign create commits once and replays the original receipt`() {
        postCampaign(CREATE_CAMPAIGN_ID, "create-key-001")
            .exchange()
            .expectStatus().isCreated
            .expectHeader().valueEquals("Location", "/api/v1/campaigns/$CREATE_CAMPAIGN_ID")
            .expectHeader().valueEquals(STREAM_POSITION_HEADER, "3")
            .expectHeader().valueEquals(PROJECTION_POSITION_HEADER, "2")
            .expectHeader().valueEquals(PROJECTION_LAG_HEADER, "1")
            .expectHeader().valueEquals("Idempotency-Replayed", "false")
            .expectBody()
            .jsonPath("$.campaignId").isEqualTo(CREATE_CAMPAIGN_ID.toString())
            .jsonPath("$.state").isEqualTo("DRAFT")
            .jsonPath("$.revision").isEqualTo(0)
            .jsonPath("$.policyVersion").isEqualTo(0)
            .jsonPath("$.remainingCapacity").isEqualTo(20)

        postCampaign(CREATE_CAMPAIGN_ID, "create-key-001")
            .exchange()
            .expectStatus().isCreated
            .expectHeader().valueEquals(STREAM_POSITION_HEADER, "3")
            .expectHeader().valueEquals("Idempotency-Replayed", "true")

        transaction(database) {
            val event = EventLog.selectAll()
                .where { EventLog.streamId eq CREATE_CAMPAIGN_ID }
                .single()

            event[EventLog.actorSurrogate] shouldNotBeEqualTo "operator-a"
            event[EventLog.actorHmacKeyVersion] shouldBeEqualTo 1

            SubjectIdentityMappings.selectAll().count() shouldBeEqualTo 1L
            SubjectIdentityMappings.selectAll()
                .single()[SubjectIdentityMappings.identityDigest] shouldNotBeEqualTo "operator-a"

            IdempotencyReceipts.selectAll().single()[IdempotencyReceipts.hmacKeyVersion] shouldBeEqualTo 1
        }
    }

    @Test
/**
 * 이 시나리오는 "live campaign create rejects same key with a different fingerprint" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live campaign create rejects same key with a different fingerprint`() {
        postCampaign(CREATE_CAMPAIGN_ID, "create-key-002")
            .exchange()
            .expectStatus().isCreated

        postCampaign(CREATE_CAMPAIGN_ID, "create-key-002", capacity = 21)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("IDEMPOTENCY_FINGERPRINT_CONFLICT")
            .jsonPath("$.reason").isEqualTo("command conflicts with current state")
            .jsonPath("$.exception").doesNotExist()
    }

    @Test
/**
 * 이 시나리오는 "identity erasure preserves immutable history and re-registration creates a new
 * surrogate" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `identity erasure preserves immutable history and re-registration creates a new surrogate`() {
        postCampaign(CREATE_CAMPAIGN_ID, "erase-key-001")
            .exchange()
            .expectStatus().isCreated

        val firstSurrogate = transaction(database) {
            EventLog.selectAll()
                .where { EventLog.streamId eq CREATE_CAMPAIGN_ID }
                .single()[EventLog.actorSurrogate]
        }

        identities.erase(TenantId(TENANT), "campaign-principal", "operator-a") shouldBeEqualTo 1

        transaction(database) {
            SubjectIdentityMappings.selectAll().count() shouldBeEqualTo 0L
            EventLog.selectAll()
                .where { EventLog.streamId eq CREATE_CAMPAIGN_ID }
                .single()[EventLog.actorSurrogate] shouldBeEqualTo firstSurrogate
        }

        postCampaign(SECOND_CREATE_CAMPAIGN_ID, "erase-key-002")
            .exchange()
            .expectStatus().isCreated

        val secondSurrogate = transaction(database) {
            EventLog.selectAll()
                .where { EventLog.streamId eq SECOND_CREATE_CAMPAIGN_ID }
                .single()[EventLog.actorSurrogate]
        }
        secondSurrogate shouldNotBeEqualTo firstSurrogate
    }

    @Test
/**
 * 이 시나리오는 "idempotent replay fails closed when its retained HMAC key is unavailable" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `idempotent replay fails closed when its retained HMAC key is unavailable`() {
        postCampaign(CREATE_CAMPAIGN_ID, "replay-key-001")
            .exchange()
            .expectStatus().isCreated

        transaction(database) {
            IdempotencyReceipts.update(
                where = { IdempotencyReceipts.resourceId eq CREATE_CAMPAIGN_ID.toString() },
            ) { row ->
                row[IdempotencyReceipts.hmacKeyVersion] = 99
            }
        }

        postCampaign(CREATE_CAMPAIGN_ID, "replay-key-001")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("REPLAY_KEY_UNAVAILABLE")
            .jsonPath("$.exception").doesNotExist()
    }

    @Test
/**
 * 이 시나리오는 "live campaign create maps stream conflict and validation without leaking internals" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live campaign create maps stream conflict and validation without leaking internals`() {
        postCampaign(CAMPAIGN_ID, "existing-key-001")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("CONCURRENT_MODIFICATION")
            .jsonPath("$.exception").doesNotExist()

        postCampaign(CREATE_CAMPAIGN_ID, "invalid-key-001", capacity = 0)
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.reason").isEqualTo("request validation failed")
            .jsonPath("$.exception").doesNotExist()
    }

    @Test
/**
 * 이 시나리오는 "live campaign create maps malformed JSON and incompatible body types to redacted client
 * errors" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live campaign create maps malformed JSON and incompatible body types to redacted client errors`(
        @Autowired runtimeWorkers: EventSourcedRuntimeWorkers,
    ) {
        check(runtimeWorkers is EventSourcedProjectionRuntime) {
            "Spring must compose the concrete projection runtime"
        }
        listOf(
            """{"campaignId":""",
            """
            {
              "campaignId": "$CREATE_CAMPAIGN_ID",
              "startsAt": "2026-08-01T00:00:00Z",
              "endsAt": "2026-08-31T00:00:00Z",
              "capacity": "not-a-number",
              "perUserLimit": 2,
              "redemptionTtlSeconds": 3600
            }
            """.trimIndent(),
        ).forEachIndexed { index, body ->
            client
                .post()
                .uri("/operator/api/v1/campaigns")
                .contentType(MediaType.APPLICATION_JSON)
                .header(TENANT_HEADER, TENANT)
                .header(PRINCIPAL_HEADER, "operator-a")
                .header(IDEMPOTENCY_HEADER, "malformed-body-$index")
                .header("If-None-Match", "*")
                .operatorAccessHeaders()
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
                .jsonPath("$.reason").isEqualTo("request validation failed")
                .jsonPath("$.exception").doesNotExist()
        }
    }

    @Test
/**
 * 이 시나리오는 "live rebuild start replays the same request and rejects a stale active token" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live rebuild start replays the same request and rejects a stale active token`() {
        startRebuild("rebuild-start-001", expectedToken = 1)
            .exchange()
            .expectStatus().isAccepted
            .expectHeader().valueEquals("X-Idempotent-Replay", "false")
            .expectBody()
            .jsonPath("$.generation").isEqualTo(2)
            .jsonPath("$.state").isEqualTo("BUILDING")
            .jsonPath("$.fencingToken").isEqualTo(1)

        startRebuild("rebuild-start-001", expectedToken = 1)
            .exchange()
            .expectStatus().isAccepted
            .expectHeader().valueEquals("X-Idempotent-Replay", "true")
            .expectBody()
            .jsonPath("$.generation").isEqualTo(2)

        startRebuild("rebuild-start-stale-001", expectedToken = 99)
            .exchange()
            .expectStatus().isEqualTo(412)
            .expectHeader().valueEquals(EXPECTED_GENERATION_TOKEN_HEADER, "1")
            .expectBody()
            .jsonPath("$.code").isEqualTo("STALE_GENERATION_TOKEN")
            .jsonPath("$.exception").doesNotExist()
    }

    @Test
/**
 * 이 시나리오는 "live rebuild status and cancel enforce the latest generation token" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live rebuild status and cancel enforce the latest generation token`() {
        startRebuild("rebuild-start-002", expectedToken = 1)
            .exchange()
            .expectStatus().isAccepted

        getRebuild(generation = 2, idempotencyKey = "rebuild-status-001", expectedToken = 1)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.state").isEqualTo("BUILDING")

        postRebuildAction(
            generation = 2,
            action = "cancel",
            idempotencyKey = "rebuild-cancel-001",
            expectedToken = 1,
        ).exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.state").isEqualTo("CANCELLING")
            .jsonPath("$.fencingToken").isEqualTo(2)

        getRebuild(generation = 2, idempotencyKey = "rebuild-status-stale-001", expectedToken = 1)
            .exchange()
            .expectStatus().isEqualTo(412)
            .expectHeader().valueEquals(EXPECTED_GENERATION_TOKEN_HEADER, "2")
    }

    @Test
/**
 * 이 시나리오는 "live rebuild rejects cancellation after the generation is active" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live rebuild rejects cancellation after the generation is active`() {
        postRebuildAction(
            generation = 1,
            action = "cancel",
            idempotencyKey = "rebuild-active-cancel-001",
            expectedToken = 1,
        ).exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("REBUILD_NOT_CANCELLABLE")
            .jsonPath("$.exception").doesNotExist()
    }

    @Test
/**
 * 이 시나리오는 "live rebuild resumes only a retryable failed generation with a fresh token" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live rebuild resumes only a retryable failed generation with a fresh token`() {
        transaction(database) {
            val rebuilds = ProjectionRebuildRepository()
            val candidate = rebuilds.start(PROJECTION, targetPosition = 2, now = NOW)
            rebuilds.fail(candidate.key, candidate.fencingToken, retryable = true, now = NOW).shouldBeTrue()
        }

        postRebuildAction(
            generation = 2,
            action = "resume",
            idempotencyKey = "rebuild-resume-001",
            expectedToken = 1,
        ).exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.state").isEqualTo("BUILDING")
            .jsonPath("$.fencingToken").isEqualTo(2)
            .jsonPath("$.retryableFailure").isEqualTo(true)
    }

    @Test
/**
 * 이 시나리오는 "live poison retry resolves the event atomically and replays its audit receipt" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live poison retry resolves the event atomically and replays its audit receipt`() {
        appendCapacityChangeAndPoison(clock.instant().minusSeconds(POISON_READY_AGE_SECONDS))

        retryPoison("poison-retry-001", expectedToken = 1)
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-Idempotent-Replay", "false")
            .expectBody()
            .jsonPath("$.eventId").isEqualTo(POISON_EVENT_ID)
            .jsonPath("$.state").isEqualTo("RESOLVED")
            .jsonPath("$.attempts").isEqualTo(1)
            .jsonPath("$.checkpointPosition").isEqualTo(3)

        retryPoison("poison-retry-001", expectedToken = 1)
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-Idempotent-Replay", "true")
            .expectBody()
            .jsonPath("$.replayed").isEqualTo(true)

        transaction(database) {
            ProjectionPoisonEvents
                .selectAll()
                .where { ProjectionPoisonEvents.eventId eq UUID.fromString(POISON_EVENT_ID) }
                .single()[ProjectionPoisonEvents.state]
                .shouldBeEqualTo(ProjectionPoisonState.RESOLVED)

            OperatorAudits
                .selectAll()
                .where { OperatorAudits.action eq OperatorAuditAction.POISON_RETRIED }
                .count()
                .shouldBeEqualTo(1L)
        }
    }

    @Test
/**
 * 이 시나리오는 "live poison retry exposes bounded backoff without leaking internals" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live poison retry exposes bounded backoff without leaking internals`() {
        appendCapacityChangeAndPoison(clock.instant())

        retryPoison("poison-retry-backoff-001", expectedToken = 1)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectHeader().valueEquals("Retry-After", "1")
            .expectBody()
            .jsonPath("$.code").isEqualTo("POISON_RETRY_BACKOFF")
            .jsonPath("$.exception").doesNotExist()
    }

    @Test
/**
 * 이 시나리오는 "live reconciliation reports durable positions and fences stale tokens" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live reconciliation reports durable positions and fences stale tokens`() {
        reconcile("reconciliation-001", expectedToken = 1)
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-Idempotent-Replay", "false")
            .expectBody()
            .jsonPath("$.streamPosition").isEqualTo(2)
            .jsonPath("$.checkpointPosition").isEqualTo(2)
            .jsonPath("$.lag").isEqualTo(0)
            .jsonPath("$.failedPoisonCount").isEqualTo(0)

        reconcile("reconciliation-001", expectedToken = 1)
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-Idempotent-Replay", "true")

        reconcile("reconciliation-stale-001", expectedToken = 99)
            .exchange()
            .expectStatus().isEqualTo(412)
            .expectHeader().valueEquals(EXPECTED_GENERATION_TOKEN_HEADER, "1")
            .expectBody()
            .jsonPath("$.code").isEqualTo("STALE_GENERATION_TOKEN")
            .jsonPath("$.exception").doesNotExist()
    }

    @Test
/**
 * 이 시나리오는 "live operator boundary rejects ambient credentials and untrusted origins before
 * dispatch" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live operator boundary rejects ambient credentials and untrusted origins before dispatch`() {
        client
            .post()
            .uri("/operator/api/v1/campaigns")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{}""")
            .exchange()
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("OPERATOR_ACCESS_DENIED")

        client
            .post()
            .uri("/operator/api/v1/campaigns")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Workshop-Operator-Secret", "wrong-secret")
            .header("X-Workshop-Operator-Guard", "workshop-operator-guard")
            .header("X-Workshop-Operator-Role", "OPERATOR")
            .header("Origin", "https://untrusted.example")
            .header(TENANT_HEADER, TENANT)
            .bodyValue("""{}""")
            .exchange()
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("OPERATOR_ACCESS_DENIED")
    }

    @Test
/**
 * 이 시나리오는 "live SSE starts with snapshot and reconnects with separate position cursor" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live SSE starts with snapshot and reconnects with separate position cursor`() {
        sseProperties.heartbeatInterval shouldBeEqualTo 100.millis()

        val first = firstSseEvent()
        val cursor = first.single { line -> line.startsWith("id:") }.substringAfter("id:")

        first.any { line -> line == "event:snapshot" }.shouldBeTrue()
        first.any { line -> line.contains("\"campaignId\":\"$CAMPAIGN_ID\"") }.shouldBeTrue()
        cursor.split(':').size shouldBeEqualTo 3

        val resumed = firstSseEvent(cursor)

        log.debug { "resumed: $resumed" }
        resumed.any { line -> line == "event:snapshot" }.shouldBeTrue()
        resumed.single { line -> line.startsWith("id:") }.substringAfter("id:") shouldBeEqualTo cursor
    }

/**
 * firstSseEvent은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun firstSseEvent(lastEventId: String? = null): List<String> =
        Socket("127.0.0.1", serverPort).use { socket ->
            socket.soTimeout = SSE_EVENT_TIMEOUT.toMillis().toInt()
            socket.getOutputStream().bufferedWriter().apply {
                append("GET /api/v1/campaigns/$CAMPAIGN_ID/events HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$serverPort\r\n")
                append("Accept: ${MediaType.TEXT_EVENT_STREAM_VALUE}\r\n")
                append("$TENANT_HEADER: $TENANT\r\n")
                append("$PRINCIPAL_HEADER: sse-client\r\n")
                lastEventId?.let { cursor -> append("Last-Event-ID: $cursor\r\n") }
                append("Connection: close\r\n\r\n")
                flush()
            }
            socket.getInputStream().bufferedReader().let { reader ->
/**
 * responseHeaders은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val responseHeaders = generateSequence(reader::readLine).takeWhile(String::isNotEmpty).toList()
                responseHeaders.first().contains(" 200 ").shouldBeTrue()
                generateSequence(reader::readLine).takeWhile(String::isNotEmpty).toList()
            }
        }

/**
 * getCampaign은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun getCampaign(minimumPosition: Long): WebTestClient.RequestHeadersSpec<*> =
        client
            .get()
            .uri("/api/v1/campaigns/$CAMPAIGN_ID")
            .header(TENANT_HEADER, TENANT)
            .header(PRINCIPAL_HEADER, "principal-a")
            .header(MIN_STREAM_POSITION_HEADER, minimumPosition.toString())

/**
 * postCampaign은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun postCampaign(
        campaignId: UUID,
        idempotencyKey: String,
        capacity: Int = 20,
    ): WebTestClient.RequestHeadersSpec<*> =
        client
            .post()
            .uri("/operator/api/v1/campaigns")
            .contentType(MediaType.APPLICATION_JSON)
            .header(TENANT_HEADER, TENANT)
            .header(PRINCIPAL_HEADER, "operator-a")
            .header(IDEMPOTENCY_HEADER, idempotencyKey)
            .header("If-None-Match", "*")
            .operatorAccessHeaders()
            .bodyValue(
                """
                {
                  "campaignId": "$campaignId",
                  "startsAt": "2026-08-01T00:00:00Z",
                  "endsAt": "2026-08-31T00:00:00Z",
                  "capacity": $capacity,
                  "perUserLimit": 2,
                  "redemptionTtlSeconds": 3600
                }
                """.trimIndent(),
            )

/**
 * startRebuild은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun startRebuild(
        idempotencyKey: String,
        expectedToken: Long,
    ): WebTestClient.RequestHeadersSpec<*> =
        client
            .post()
            .uri("/operator/api/v1/projections/$PROJECTION/rebuilds")
            .contentType(MediaType.APPLICATION_JSON)
            .rebuildHeaders(idempotencyKey, expectedToken)
            .bodyValue("""{"targetPosition":2}""")

/**
 * getRebuild은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun getRebuild(
        generation: Long,
        idempotencyKey: String,
        expectedToken: Long,
    ): WebTestClient.RequestHeadersSpec<*> =
        client
            .get()
            .uri("/operator/api/v1/projections/$PROJECTION/rebuilds/$generation")
            .rebuildHeaders(idempotencyKey, expectedToken)

/**
 * postRebuildAction은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun postRebuildAction(
        generation: Long,
        action: String,
        idempotencyKey: String,
        expectedToken: Long,
    ): WebTestClient.RequestHeadersSpec<*> =
        client
            .post()
            .uri("/operator/api/v1/projections/$PROJECTION/rebuilds/$generation/$action")
            .contentType(MediaType.APPLICATION_JSON)
            .rebuildHeaders(idempotencyKey, expectedToken)

/**
 * retryPoison은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun retryPoison(
        idempotencyKey: String,
        expectedToken: Long,
    ): WebTestClient.RequestHeadersSpec<*> =
        client
            .post()
            .uri(
                "/operator/api/v1/projections/$PROJECTION/generations/$GENERATION/" +
                        "poison-events/$POISON_EVENT_ID/retry",
            )
            .contentType(MediaType.APPLICATION_JSON)
            .rebuildHeaders(idempotencyKey, expectedToken)

/**
 * reconcile은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun reconcile(
        idempotencyKey: String,
        expectedToken: Long,
    ): WebTestClient.RequestHeadersSpec<*> =
        client
            .post()
            .uri("/operator/api/v1/projections/$PROJECTION/generations/$GENERATION/reconciliation")
            .contentType(MediaType.APPLICATION_JSON)
            .rebuildHeaders(idempotencyKey, expectedToken)

/**
 * appendCapacityChangeAndPoison은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun appendCapacityChangeAndPoison(poisonedAt: Instant) {
        val eventStore = EventStoreRepository(ExposedEventStoreTransactionRunner(database))
        transaction(database) {
            eventStore.appendAll(
                listOf(
                    ExpectedAppend(
                        stream = StreamKey(TenantId(TENANT), "campaign", CAMPAIGN_ID),
                        expectedVersion = 2,
                        events =
                            listOf(
                                event(
                                    POISON_EVENT_ID,
                                    "campaign.capacity-changed",
                                    """{"capacity":120}""",
                                ),
                            ),
                    ),
                ),
            )
            val leases = ProjectionLeaseRepository()
            val lease = leases.acquire(PROJECTION, GENERATION, "poison-fixture", poisonedAt).shouldNotBeNull()
            val poisonEvent = ExposedProjectionEventReader().loadAfter(2).events.single()
            ProjectionRepository(leases).poison(
                ProjectionKey(PROJECTION, GENERATION),
                lease,
                poisonEvent,
                "TRANSIENT_DECODER_FAILURE",
                poisonedAt,
            )
        }
    }

    private fun <S: WebTestClient.RequestHeadersSpec<S>> S.rebuildHeaders(
        idempotencyKey: String,
        expectedToken: Long,
    ): S =
        header(TENANT_HEADER, TENANT)
            .header(PRINCIPAL_HEADER, "operator-a")
            .header(IDEMPOTENCY_HEADER, idempotencyKey)
            .header(EXPECTED_GENERATION_TOKEN_HEADER, expectedToken.toString())
            .operatorAccessHeaders()

    private fun <S: WebTestClient.RequestHeadersSpec<S>> S.operatorAccessHeaders(): S =
        header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .header(OPERATOR_ROLE_HEADER, "OPERATOR")
            .header("Origin", operatorOrigin)

/**
 * event은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun event(
        eventId: String,
        eventType: String,
        payload: String,
    ): EventToAppend =
        EventToAppend(
            eventId = UUID.fromString(eventId),
            eventType = eventType,
            schemaVersion = 1,
            payload = EventPayload(payload),
            occurredAt = NOW,
            correlationId = eventId,
            actorSurrogate = "http-test",
        )
}
