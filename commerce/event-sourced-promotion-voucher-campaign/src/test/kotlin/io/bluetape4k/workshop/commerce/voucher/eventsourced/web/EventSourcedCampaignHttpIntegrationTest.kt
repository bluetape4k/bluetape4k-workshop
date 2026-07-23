package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.OperatorAuditAction
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendFences
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.CampaignProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExposedEventStoreTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.IdempotencyReceipts
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.OperatorAudits
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionCheckpoints
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionLeases
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionProcessedEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamHeads
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ExposedProjectionEventReader
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionLeaseRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRebuildRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRepository
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventSourcedCampaignHttpIntegrationTest
    @Autowired
    constructor(
        private val registration: EventSourcedExposedDatabaseRegistration,
        dataSource: DataSource,
        private val clock: Clock,
        @LocalServerPort port: Int,
    ) {
    private val database get() = registration.database
    private val client: WebTestClient

    init {
        check(dataSource is HikariDataSource) { "live HTTP integration must use Spring's Hikari DataSource" }
        val httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build()
        client =
            WebTestClient
                .bindToServer(JdkClientHttpConnector(httpClient))
                .baseUrl("http://127.0.0.1:$port")
                .responseTimeout(HTTP_TIMEOUT)
                .build()
    }

    @BeforeEach
    fun createProjection() {
        transaction(database) {
            SchemaUtils.create(*TABLES)
            ProjectionRebuildRepository().initializeActive(PROJECTION, NOW)
        }
        val eventStore = EventStoreRepository(ExposedEventStoreTransactionRunner(database))
        transaction(database) {
            eventStore.appendAll(
                listOf(
                    ExpectedAppend(
                        stream = StreamKey(TenantId(TENANT), "campaign", CAMPAIGN_ID),
                        expectedVersion = 0,
                        events =
                            listOf(
                                event(
                                    EVENT_CREATED_ID,
                                    "campaign.created",
                                    """
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
            ProjectionRepository(leases).applyBatch(ProjectionKey(PROJECTION, GENERATION), lease, batch.events, NOW)
        }
    }

    @AfterEach
    fun dropProjection() = transaction(database) { SchemaUtils.drop(*TABLES) }

    @Test
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
            .jsonPath("$.revision").isEqualTo(2)
            .jsonPath("$.policyVersion").isEqualTo(1)
            .jsonPath("$.capacity").isEqualTo(100)
            .jsonPath("$.allocatedCount").isEqualTo(0)
            .jsonPath("$.remainingCapacity").isEqualTo(100)
    }

    @Test
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
    fun `live campaign create commits once and replays the original receipt`() {
        postCampaign(CREATE_CAMPAIGN_ID, "create-key-001")
            .exchange()
            .expectStatus().isCreated
            .expectHeader().valueEquals("Location", "/api/v1/campaigns/$CREATE_CAMPAIGN_ID")
            .expectHeader().valueEquals(STREAM_POSITION_HEADER, "3")
            .expectHeader().valueEquals(PROJECTION_POSITION_HEADER, "2")
            .expectHeader().valueEquals(PROJECTION_LAG_HEADER, "1")
            .expectHeader().valueEquals("X-Idempotent-Replay", "false")
            .expectBody()
            .jsonPath("$.campaignId").isEqualTo(CREATE_CAMPAIGN_ID.toString())
            .jsonPath("$.state").isEqualTo("DRAFT")
            .jsonPath("$.revision").isEqualTo(1)
            .jsonPath("$.policyVersion").isEqualTo(1)
            .jsonPath("$.remainingCapacity").isEqualTo(20)

        postCampaign(CREATE_CAMPAIGN_ID, "create-key-001")
            .exchange()
            .expectStatus().isCreated
            .expectHeader().valueEquals(STREAM_POSITION_HEADER, "3")
            .expectHeader().valueEquals("X-Idempotent-Replay", "true")

        transaction(database) {
            EventLog.selectAll()
                .where { EventLog.streamId eq CREATE_CAMPAIGN_ID }
                .count()
                .shouldBeEqualTo(1L)
        }
    }

    @Test
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
    fun `live rebuild status and cancel enforce the latest generation token`() {
        startRebuild("rebuild-start-002", expectedToken = 1).exchange().expectStatus().isAccepted

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
    fun `live rebuild resumes only a retryable failed generation with a fresh token`() {
        transaction(database) {
            val rebuilds = ProjectionRebuildRepository()
            val candidate = rebuilds.start(PROJECTION, targetPosition = 2, now = NOW)
            rebuilds.fail(candidate.key, candidate.fencingToken, retryable = true, now = NOW).shouldBeEqualTo(true)
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

    private fun getCampaign(minimumPosition: Long): WebTestClient.RequestHeadersSpec<*> =
        client
            .get()
            .uri("/api/v1/campaigns/$CAMPAIGN_ID")
            .header(TENANT_HEADER, TENANT)
            .header(PRINCIPAL_HEADER, "principal-a")
            .header(MIN_STREAM_POSITION_HEADER, minimumPosition.toString())

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

    private fun getRebuild(
        generation: Long,
        idempotencyKey: String,
        expectedToken: Long,
    ): WebTestClient.RequestHeadersSpec<*> =
        client
            .get()
            .uri("/operator/api/v1/projections/$PROJECTION/rebuilds/$generation")
            .rebuildHeaders(idempotencyKey, expectedToken)

    private fun postRebuildAction(
        generation: Long,
        action: String,
        idempotencyKey: String,
        expectedToken: Long,
    ): WebTestClient.RequestHeadersSpec<*> =
        client
            .post()
            .uri("/operator/api/v1/projections/$PROJECTION/rebuilds/$generation/$action")
            .rebuildHeaders(idempotencyKey, expectedToken)

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
            .rebuildHeaders(idempotencyKey, expectedToken)

    private fun reconcile(
        idempotencyKey: String,
        expectedToken: Long,
    ): WebTestClient.RequestHeadersSpec<*> =
        client
            .post()
            .uri("/operator/api/v1/projections/$PROJECTION/generations/$GENERATION/reconciliation")
            .rebuildHeaders(idempotencyKey, expectedToken)

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

    companion object {
        private val POSTGRES = PostgreSQLServer.Launcher.postgres
        private val HTTP_TIMEOUT: Duration = Duration.ofSeconds(60)
        private const val TENANT = "tenant-a"
        private const val PROJECTION = "voucher-lifecycle"
        private const val GENERATION = 1L
        private const val EVENT_CREATED_ID = "0198a1b2-c3d4-7e5f-8123-456789abc301"
        private const val EVENT_ACTIVATED_ID = "0198a1b2-c3d4-7e5f-8123-456789abc302"
        private const val POISON_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc303"
        private const val POISON_READY_AGE_SECONDS = 20L
        private val CAMPAIGN_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc300")
        private val CREATE_CAMPAIGN_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc399")
        private val NOW = Instant.parse("2026-07-23T00:00:00Z")
        private val TABLES =
            arrayOf(
                EventLog,
                StreamHeads,
                AppendFences,
                IdempotencyReceipts,
                OperatorAudits,
                ProjectionGenerations,
                ActiveProjectionGenerations,
                ProjectionLeases,
                ProjectionProcessedEvents,
                ProjectionPoisonEvents,
                ProjectionReadModels,
                CampaignProjectionReadModels,
                ProjectionCheckpoints,
            )

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { POSTGRES.jdbcUrl }
            registry.add("spring.datasource.username") { POSTGRES.username.shouldNotBeNull() }
            registry.add("spring.datasource.password") { POSTGRES.password.shouldNotBeNull() }
            registry.add("management.datadog.metrics.export.enabled") { "false" }
        }
    }
}
