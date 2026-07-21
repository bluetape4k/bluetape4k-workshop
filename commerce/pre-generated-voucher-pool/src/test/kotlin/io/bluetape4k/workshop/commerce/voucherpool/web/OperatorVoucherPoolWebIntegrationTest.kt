@file:Suppress("LargeClass", "LongMethod", "MagicNumber", "VarCouldBeVal")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionLimits
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionNamespace
import io.bluetape4k.workshop.commerce.voucherpool.admission.VoucherPoolAdmissionGate
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchSourceKind
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateCampaignCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateImportBatchCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.RevokeAggregateType
import io.bluetape4k.workshop.commerce.voucherpool.application.RevokePreviewSnapshot
import io.bluetape4k.workshop.commerce.voucherpool.application.ReservationService
import io.bluetape4k.workshop.commerce.voucherpool.application.ReserveVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.applied
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.worker.VoucherPoolWorkers
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunRequest
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.core.io.ClassPathResource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

@Import(OperatorVoucherPoolWebTestConfiguration::class)
internal class OperatorVoucherPoolWebIntegrationTest : AbstractVoucherPoolIntegrationTest() {
    @Autowired
    private lateinit var campaigns: CampaignBatchCommandService

    @Autowired
    private lateinit var reservations: ReservationService

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var workers: VoucherPoolWorkers

    @BeforeEach
    fun reset() {
        VoucherPoolMigrationRunner(
            dataSource,
            VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            537_011L,
        ).migrate()
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("TRUNCATE TABLE voucher_pool_campaigns CASCADE") }
        }
    }

    @Test
    fun `operator query routes expose safe tenant scoped projections`() {
        val fixture = activePool("projections")
        val reservation =
            reservations.reserve(
                ReserveVoucherCommand(TENANT, fixture.campaignId, PRINCIPAL, "operator-web-reserve"),
            ).applied()
        expireReservation(reservation.reservationId)

        operatorGet("/operator/api/v1/batches/${fixture.batchId}")
            .exchange().expectStatus().isOk
            .expectHeader().exists(REQUEST_ID_HEADER)
            .expectBody()
            .jsonPath("$.batchId").isEqualTo(fixture.batchId.toString())
            .jsonPath("$.campaignId").isEqualTo(fixture.campaignId.toString())
            .jsonPath("$.state").isEqualTo("ACTIVE")
            .jsonPath("$.requestId").isNotEmpty
            .jsonPath("$.observedAt").isNotEmpty
            .jsonPath("$.code").doesNotExist()

        operatorGet(
            "/operator/api/v1/pool-depth?campaignId=${fixture.campaignId}&batchId=${fixture.batchId}",
        ).exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.counts.RESERVED").isEqualTo(1)
            .jsonPath("$.counts.AVAILABLE").isEqualTo(1)
            .jsonPath("$.eligibleAvailable").isEqualTo(1)
            .jsonPath("$.expiredButNotTerminalized").isEqualTo(1)

        operatorGet("/operator/api/v1/reservations/stuck?campaignId=${fixture.campaignId}&limit=1")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.items[0].reservationId").isEqualTo(reservation.reservationId.toString())
            .jsonPath("$.items[0].state").isEqualTo("ACTIVE")
            .jsonPath("$.nextCursor").doesNotExist()
            .jsonPath("$.items[0].userDigest").doesNotExist()
    }

    @Test
    fun `operator query scope mismatch and invalid cursor are closed`() {
        val fixture = activePool("scope")

        operatorGet("/operator/api/v1/batches/${fixture.batchId}", tenant = "wrong-tenant")
            .exchange().expectStatus().isNotFound
            .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
        operatorGet(
            "/operator/api/v1/pool-depth?campaignId=${fixture.campaignId}&batchId=${fixture.batchId}",
            tenant = "wrong-tenant",
        ).exchange().expectStatus().isNotFound
        operatorGet("/operator/api/v1/reservations/stuck?campaignId=${fixture.campaignId}&cursor=not-a-cursor")
            .exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")
        operatorGet("/operator/api/v1/reservations/stuck?campaignId=${fixture.campaignId}&limit=101")
            .exchange().expectStatus().isBadRequest
    }

    @Test
    fun `operator campaign commands enforce create and revision preconditions with safe replay`() {
        val campaignId = UUID.randomUUID()
        val now = Instant.now()
        val createBody = mapOf(
            "campaignId" to campaignId,
            "startsAt" to now.minusSeconds(60),
            "endsAt" to now.plusSeconds(3_600),
            "perUserLimit" to 3,
            "reservationTtlSeconds" to 300,
            "allocationTtlSeconds" to 1_800,
            "replacementAllowance" to 1,
        )

        operatorPost("/operator/api/v1/campaigns", "campaign-create", ifNoneMatch = "*", body = createBody)
            .expectStatus().isCreated
            .expectHeader().valueEquals("Location", "/operator/api/v1/campaigns/$campaignId")
            .expectHeader().valueEquals("ETag", "\"0\"")
            .expectBody()
            .jsonPath("$.campaignId").isEqualTo(campaignId.toString())
            .jsonPath("$.state").isEqualTo("DRAFT")
            .jsonPath("$.observedAt").isNotEmpty
            .jsonPath("$.requestId").isNotEmpty

        operatorPost("/operator/api/v1/campaigns", "campaign-create", ifNoneMatch = "*", body = createBody)
            .expectStatus().isOk
            .expectHeader().valueEquals("Duplicate-Request", "true")

        val policyBody = mapOf(
            "perUserLimit" to 4,
            "reservationTtlSeconds" to 600,
            "allocationTtlSeconds" to 2_400,
            "replacementAllowance" to 1,
        )
        operatorPost(
            "/operator/api/v1/campaigns/$campaignId/policy",
            "campaign-policy",
            ifMatch = "\"0\"",
            body = policyBody,
        ).expectStatus().isOk
            .expectHeader().valueEquals("ETag", "\"1\"")
            .expectBody().jsonPath("$.policyVersion").isEqualTo(2)

        operatorPost(
            "/operator/api/v1/campaigns/$campaignId/activate",
            "campaign-activate",
            ifMatch = "\"1\"",
        ).expectStatus().isOk
            .expectHeader().valueEquals("ETag", "\"2\"")
            .expectBody().jsonPath("$.state").isEqualTo("ACTIVE")
    }

    @Test
    fun `operator import and generate batch commands use authoritative revisions`() {
        val importCampaign = activeCampaign("http-import")
        val importBatch = UUID.randomUUID()
        val importManifest = digestHex(7)
        val now = Instant.now()
        operatorPost(
            "/operator/api/v1/batches/import",
            "batch-import-create",
            ifNoneMatch = "*",
            body = mapOf(
                "batchId" to importBatch,
                "campaignId" to importCampaign,
                "manifestDigest" to importManifest,
                "expectedCount" to 2,
                "activatesAt" to now.minusSeconds(30),
                "codes" to listOf("HTTP-IMPORT-0"),
            ),
        ).expectStatus().isCreated
            .expectHeader().valueEquals("ETag", "\"1\"")

        operatorPost(
            "/operator/api/v1/batches/$importBatch/import-chunks",
            "batch-import-chunk",
            ifMatch = "\"1\"",
            body = mapOf(
                "campaignId" to importCampaign,
                "firstOrdinal" to 1,
                "manifestDigest" to importManifest,
                "codes" to listOf("HTTP-IMPORT-1"),
            ),
        ).expectStatus().isOk
            .expectHeader().valueEquals("ETag", "\"2\"")
            .expectBody().jsonPath("$.acceptedCount").isEqualTo(2)

        operatorPost(
            "/operator/api/v1/batches/$importBatch/activate",
            "batch-import-activate",
            ifMatch = "\"2\"",
            body = emptyMap<String, String>(),
        ).expectStatus().isOk
            .expectHeader().valueEquals("ETag", "\"3\"")
            .expectBody().jsonPath("$.state").isEqualTo("ACTIVE")

        val generateCampaign = activeCampaign("http-generate")
        val generateBatch = UUID.randomUUID()
        val generateManifest = digestHex(11)
        operatorPost(
            "/operator/api/v1/batches/generate",
            "batch-generate-create",
            ifNoneMatch = "*",
            body = mapOf(
                "batchId" to generateBatch,
                "campaignId" to generateCampaign,
                "manifestDigest" to generateManifest,
                "expectedCount" to 1,
                "activatesAt" to now.minusSeconds(30),
            ),
        ).expectStatus().isCreated
            .expectHeader().valueEquals("ETag", "\"0\"")

        operatorPost(
            "/operator/api/v1/batches/$generateBatch/generate-chunks",
            "batch-generate-chunk",
            ifMatch = "\"0\"",
            body = mapOf(
                "campaignId" to generateCampaign,
                "firstOrdinal" to 0,
                "manifestDigest" to generateManifest,
                "count" to 1,
            ),
        ).expectStatus().isOk
            .expectHeader().valueEquals("ETag", "\"1\"")
            .expectBody().jsonPath("$.acceptedCount").isEqualTo(1)
    }

    @Test
    fun `operator command precondition failures use bounded errors`() {
        val campaignId = UUID.randomUUID()
        val now = Instant.now()
        val body = mapOf(
            "campaignId" to campaignId,
            "startsAt" to now,
            "endsAt" to now.plusSeconds(3_600),
            "perUserLimit" to 1,
            "reservationTtlSeconds" to 60,
            "allocationTtlSeconds" to 60,
            "replacementAllowance" to 0,
        )

        operatorPost("/operator/api/v1/campaigns", "missing-create-precondition", body = body)
            .expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")

        operatorPost("/operator/api/v1/campaigns", "stale-campaign-create", ifNoneMatch = "*", body = body)
            .expectStatus().isCreated
        operatorPost(
            "/operator/api/v1/campaigns/$campaignId/activate",
            "stale-campaign-activate",
            ifMatch = "\"99\"",
        ).expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("STALE_REVISION")
    }

    @Test
    fun `operator campaign and batch pause resume transitions use aggregate CAS`() {
        val campaignId = activeCampaign("pause-resume-campaign")

        operatorPost(
            "/operator/api/v1/campaigns/$campaignId/pause",
            "pause-campaign-http",
            ifMatch = "\"1\"",
        ).expectStatus().isOk
            .expectHeader().valueEquals("ETag", "\"2\"")
            .expectBody().jsonPath("$.state").isEqualTo("PAUSED")

        operatorPost(
            "/operator/api/v1/campaigns/$campaignId/resume",
            "resume-campaign-http",
            ifMatch = "\"2\"",
        ).expectStatus().isOk
            .expectHeader().valueEquals("ETag", "\"3\"")
            .expectBody().jsonPath("$.state").isEqualTo("ACTIVE")

        val fixture = activePool("pause-resume-batch")
        operatorPost(
            "/operator/api/v1/batches/${fixture.batchId}/pause",
            "pause-batch-http",
            ifMatch = "\"2\"",
        ).expectStatus().isOk
            .expectHeader().valueEquals("ETag", "\"3\"")
            .expectBody().jsonPath("$.state").isEqualTo("PAUSED")

        operatorPost(
            "/operator/api/v1/batches/${fixture.batchId}/resume",
            "resume-batch-http",
            ifMatch = "\"3\"",
        ).expectStatus().isOk
            .expectHeader().valueEquals("ETag", "\"4\"")
            .expectBody().jsonPath("$.state").isEqualTo("ACTIVE")
    }

    @Test
    fun `revoke preview representations redact their single use token`() {
        val token = "single-use-preview-token"
        val now = Instant.parse("2026-07-21T00:00:00Z")
        val aggregateId = UUID.randomUUID()
        val snapshot = RevokePreviewSnapshot(
            aggregateType = RevokeAggregateType.BATCH,
            aggregateId = aggregateId,
            revision = 2,
            counts = mapOf(EntryState.AVAILABLE to 2L),
            eligibleDepth = 2,
            activeReservations = 0,
            activeAllocations = 0,
            alreadyTerminalCount = 0,
            affectedCount = 2,
            previewToken = token,
            expiresAt = now.plusSeconds(60),
            observedAt = now,
        )
        val response = OperatorRevokePreviewResponse(
            aggregateType = RevokeAggregateType.BATCH,
            aggregateId = aggregateId,
            revision = 2,
            counts = snapshot.counts,
            eligibleDepth = 2,
            activeReservations = 0,
            activeAllocations = 0,
            alreadyTerminalCount = 0,
            affectedCount = 2,
            previewToken = token,
            expiresAt = snapshot.expiresAt,
            observedAt = now,
            requestId = "request-preview-redaction",
        )

        snapshot.toString().contains(token) shouldBeEqualTo false
        response.toString().contains(token) shouldBeEqualTo false
    }

    @Test
    fun `revoke preview separates mutable impact from currently eligible depth`() {
        val fixture = activePool("batch-revoke-paused-depth")
        operatorPost(
            "/operator/api/v1/batches/${fixture.batchId}/pause",
            "pause-before-revoke-preview",
            ifMatch = "\"2\"",
        ).expectStatus().isOk

        operatorPost(
            "/operator/api/v1/batches/${fixture.batchId}/revoke-preview",
            "unused-preview-key",
            ifMatch = "\"3\"",
        ).expectStatus().isOk
            .expectBody()
            .jsonPath("$.affectedCount").isEqualTo(2)
            .jsonPath("$.eligibleDepth").isEqualTo(0)
    }

    @Test
    fun `operator batch revoke consumes a revision bound preview once and hands off a durable claim`() {
        val fixture = activePool("batch-revoke")
        val other = activePool("batch-revoke-other")
        val previewBody = operatorPost(
            "/operator/api/v1/batches/${fixture.batchId}/revoke-preview",
            "unused-preview-key",
            ifMatch = "\"2\"",
        ).expectStatus().isOk
            .expectBody()
            .jsonPath("$.aggregateType").isEqualTo("BATCH")
            .jsonPath("$.aggregateId").isEqualTo(fixture.batchId.toString())
            .jsonPath("$.affectedCount").isEqualTo(2)
            .jsonPath("$.counts.AVAILABLE").isEqualTo(2)
            .jsonPath("$.previewToken").isNotEmpty
            .returnResult().responseBody ?: error("preview response is required")
        val previewToken = objectMapper.readTree(previewBody).required("previewToken").asString()
        val revokeBody = mapOf(
            "previewToken" to previewToken,
            "confirmedBatchId" to fixture.batchId,
        )

        operatorPost(
            "/operator/api/v1/batches/${other.batchId}/revoke",
            "batch-revoke-cross-resource",
            ifMatch = "\"2\"",
            body = mapOf("previewToken" to previewToken, "confirmedBatchId" to other.batchId),
        ).expectStatus().isBadRequest
        operatorGet("/operator/api/v1/batches/${other.batchId}")
            .exchange().expectStatus().isOk
            .expectBody().jsonPath("$.revision").isEqualTo(2)

        operatorPost(
            "/operator/api/v1/batches/${fixture.batchId}/revoke",
            "batch-revoke-command",
            ifMatch = "\"2\"",
            body = revokeBody,
        ).expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.aggregateType").isEqualTo("BATCH")
            .jsonPath("$.aggregateId").isEqualTo(fixture.batchId.toString())
            .jsonPath("$.state").isEqualTo("REVOKING")
            .jsonPath("$.workerCount").isEqualTo(1)

        workerClaimCount(fixture.batchId) shouldBeEqualTo 1

        operatorPost(
            "/operator/api/v1/batches/${fixture.batchId}/revoke",
            "batch-revoke-replayed-token",
            ifMatch = "\"3\"",
            body = revokeBody,
        ).expectStatus().isBadRequest
        operatorGet("/operator/api/v1/batches/${fixture.batchId}")
            .exchange().expectStatus().isOk
            .expectBody().jsonPath("$.revision").isEqualTo(3)
    }

    @Test
    fun `operator campaign revoke fans out durable claims for every revocable child batch`() {
        val campaignId = activeCampaign("campaign-revoke")
        val firstBatch = activeBatch(campaignId, "campaign-revoke-a")
        val secondBatch = activeBatch(campaignId, "campaign-revoke-b")
        val previewBody = operatorPost(
            "/operator/api/v1/campaigns/$campaignId/revoke-preview",
            "unused-campaign-preview-key",
            ifMatch = "\"1\"",
        ).expectStatus().isOk
            .expectBody()
            .jsonPath("$.aggregateType").isEqualTo("CAMPAIGN")
            .jsonPath("$.aggregateId").isEqualTo(campaignId.toString())
            .jsonPath("$.affectedCount").isEqualTo(2)
            .returnResult().responseBody ?: error("campaign preview response is required")
        val previewToken = objectMapper.readTree(previewBody).required("previewToken").asString()

        operatorPost(
            "/operator/api/v1/campaigns/$campaignId/revoke",
            "campaign-revoke-command",
            ifMatch = "\"1\"",
            body = mapOf("previewToken" to previewToken, "confirmedCampaignId" to campaignId),
        ).expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.state").isEqualTo("REVOKING")
            .jsonPath("$.revision").isEqualTo(2)
            .jsonPath("$.workerCount").isEqualTo(2)

        workerClaimCount(campaignId, "CAMPAIGN_REVOKE") shouldBeEqualTo 1
        workerClaimCount(firstBatch) shouldBeEqualTo 0
        workerClaimCount(secondBatch) shouldBeEqualTo 0

        workers.run(
            WorkerRunRequest(
                TENANT, WorkerKind.CAMPAIGN_REVOKE, campaignId, "campaign-fanout-test", requestedLimit = 1,
            ),
        ).state shouldBeEqualTo WorkerRunState.COMPLETED
        workerClaimCount(firstBatch) shouldBeEqualTo 1
        workerClaimCount(secondBatch) shouldBeEqualTo 1
    }

    @Test
    fun `campaign revoke with no child batches reaches terminal state without an orphan worker`() {
        val campaignId = activeCampaign("campaign-revoke-empty")
        val previewBody = operatorPost(
            "/operator/api/v1/campaigns/$campaignId/revoke-preview",
            "unused-empty-campaign-preview-key",
            ifMatch = "\"1\"",
        ).expectStatus().isOk
            .expectBody()
            .jsonPath("$.affectedCount").isEqualTo(0)
            .returnResult().responseBody ?: error("campaign preview response is required")
        val previewToken = objectMapper.readTree(previewBody).required("previewToken").asString()

        operatorPost(
            "/operator/api/v1/campaigns/$campaignId/revoke",
            "empty-campaign-revoke-command",
            ifMatch = "\"1\"",
            body = mapOf("previewToken" to previewToken, "confirmedCampaignId" to campaignId),
        ).expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.state").isEqualTo("REVOKED")
            .jsonPath("$.revision").isEqualTo(3)
            .jsonPath("$.workerCount").isEqualTo(0)
        workerClaimCount(campaignId, "CAMPAIGN_REVOKE") shouldBeEqualTo 0
    }

    @Test
    fun `campaign revoke ignores historical claims for already terminal child batches`() {
        val fixture = activePool("campaign-revoke-terminal-child")
        val batchPreview = operatorPost(
            "/operator/api/v1/batches/${fixture.batchId}/revoke-preview",
            "unused-terminal-child-batch-preview",
            ifMatch = "\"2\"",
        ).expectStatus().isOk.expectBody().returnResult().responseBody ?: error("batch preview is required")
        val batchToken = objectMapper.readTree(batchPreview).required("previewToken").asString()
        operatorPost(
            "/operator/api/v1/batches/${fixture.batchId}/revoke",
            "terminal-child-batch-revoke",
            ifMatch = "\"2\"",
            body = mapOf("previewToken" to batchToken, "confirmedBatchId" to fixture.batchId),
        ).expectStatus().isAccepted
        workers.run(
            WorkerRunRequest(TENANT, WorkerKind.BATCH_REVOKE, fixture.batchId, "terminal-child-worker"),
        ).state shouldBeEqualTo WorkerRunState.COMPLETED
        workerClaimCount(fixture.batchId) shouldBeEqualTo 1

        val campaignPreview = operatorPost(
            "/operator/api/v1/campaigns/${fixture.campaignId}/revoke-preview",
            "unused-terminal-child-campaign-preview",
            ifMatch = "\"1\"",
        ).expectStatus().isOk.expectBody().returnResult().responseBody ?: error("campaign preview is required")
        val campaignToken = objectMapper.readTree(campaignPreview).required("previewToken").asString()
        operatorPost(
            "/operator/api/v1/campaigns/${fixture.campaignId}/revoke",
            "terminal-child-campaign-revoke",
            ifMatch = "\"1\"",
            body = mapOf("previewToken" to campaignToken, "confirmedCampaignId" to fixture.campaignId),
        ).expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.state").isEqualTo("REVOKED")
            .jsonPath("$.workerCount").isEqualTo(0)
    }

    @Test
    fun `operator reconciliation accepts one durable batch scoped run with safe replay`() {
        val fixture = activePool("reconciliation")
        val body = mapOf("batchId" to fixture.batchId)

        operatorPost("/operator/api/v1/reconciliation/run", "reconciliation-run", body = body)
            .expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.kind").isEqualTo("RECONCILIATION")
            .jsonPath("$.scopeId").isEqualTo(fixture.batchId.toString())
            .jsonPath("$.state").isEqualTo("IDLE")
            .jsonPath("$.nextAction").isEqualTo("CLAIM_AVAILABLE")
        workerClaimCount(fixture.batchId, "RECONCILIATION") shouldBeEqualTo 1

        operatorPost("/operator/api/v1/reconciliation/run", "reconciliation-run-competing", body = body)
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("COMMAND_IN_PROGRESS")

        operatorPost("/operator/api/v1/reconciliation/run", "reconciliation-run", body = body)
            .expectStatus().isOk
            .expectHeader().valueEquals("Duplicate-Request", "true")

        workers.run(
            WorkerRunRequest(TENANT, WorkerKind.RECONCILIATION, fixture.batchId, "reconciliation-web-test"),
        ).state shouldBeEqualTo WorkerRunState.COMPLETED

        operatorPost("/operator/api/v1/reconciliation/run", "reconciliation-run-after-completion", body = body)
            .expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.state").isEqualTo("IDLE")
            .jsonPath("$.nextAction").isEqualTo("CLAIM_AVAILABLE")
        workerClaimCount(fixture.batchId, "RECONCILIATION") shouldBeEqualTo 1
    }

    @Test
    fun `operator diagnostic lookup is bounded and tenant scoped by safe request id`() {
        val fixture = activePool("diagnostic")
        val observed = operatorGet("/operator/api/v1/batches/${fixture.batchId}")
            .exchange().expectStatus().isOk
            .returnResult(ByteArray::class.java)
        val targetRequestId = checkNotNull(observed.responseHeaders.getFirst(REQUEST_ID_HEADER))

        operatorGet("/operator/api/v1/diagnostics/$targetRequestId")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.targetRequestId").isEqualTo(targetRequestId)
            .jsonPath("$.method").isEqualTo("GET")
            .jsonPath("$.path").isEqualTo("/operator/api/v1/batches/${fixture.batchId}")
            .jsonPath("$.status").isEqualTo(200)
            .jsonPath("$.requestId").isNotEmpty

        operatorGet("/operator/api/v1/diagnostics/$targetRequestId", tenant = "wrong-tenant")
            .exchange().expectStatus().isNotFound
    }

    private fun operatorGet(path: String, tenant: String = TENANT): WebTestClient.RequestHeadersSpec<*> =
        webTestClient.get().uri(path)
            .accept(MediaType.APPLICATION_JSON)
            .header("X-Workshop-Origin", "http://127.0.0.1:$port")
            .header(TENANT_HEADER, tenant)
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)

    private fun operatorPost(
        path: String,
        idempotencyKey: String,
        ifNoneMatch: String? = null,
        ifMatch: String? = null,
        body: Any = emptyMap<String, String>(),
    ): WebTestClient.ResponseSpec {
        val request = webTestClient.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header("Origin", "http://127.0.0.1:$port")
            .header(TENANT_HEADER, TENANT)
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .header(IDEMPOTENCY_HEADER, idempotencyKey)
        ifNoneMatch?.let { request.header("If-None-Match", it) }
        ifMatch?.let { request.header("If-Match", it) }
        return request.bodyValue(body).exchange()
    }

    private fun activeCampaign(name: String): UUID {
        val now = Instant.now()
        val campaignId = UUID.randomUUID()
        val campaign = campaigns.createCampaign(
            CreateCampaignCommand(
                TENANT,
                campaignId,
                now.minusSeconds(60),
                now.plusSeconds(3_600),
                VoucherPoolPolicy.of(3, 5.minutes, 30.minutes, 1),
                "operator-web-create-only-$name",
            ),
        ).applied()
        campaigns.activateCampaign(
            CampaignRevisionCommand(TENANT, campaignId, campaign.revision, "operator-web-activate-only-$name"),
        ).applied()
        return campaignId
    }

    private fun digestHex(seed: Int): String =
        ByteArray(32) { index -> (seed + index).toByte() }.joinToString("") { "%02x".format(it) }

    private fun activePool(name: String): OperatorFixture {
        val now = Instant.now()
        val campaignId = UUID.randomUUID()
        val batchId = UUID.randomUUID()
        val campaign =
            campaigns.createCampaign(
                CreateCampaignCommand(
                    TENANT,
                    campaignId,
                    now.minusSeconds(60),
                    now.plusSeconds(3_600),
                    VoucherPoolPolicy.of(3, 5.minutes, 30.minutes, 1),
                    "operator-web-create-campaign-$name",
                ),
            ).applied()
        campaigns.activateCampaign(
            CampaignRevisionCommand(TENANT, campaignId, campaign.revision, "operator-web-activate-campaign-$name"),
        ).applied()
        val batch =
            campaigns.createImportBatch(
                CreateImportBatchCommand(
                    TENANT,
                    batchId,
                    campaignId,
                    BatchSourceKind.IMPORTED,
                    digest(1),
                    digest(2),
                    2,
                    now.minusSeconds(30),
                    initialCodes = listOf("OPERATOR-WEB-$name-A", "OPERATOR-WEB-$name-B"),
                    idempotencyKey = "operator-web-create-batch-$name",
                ),
            ).applied()
        campaigns.activateBatch(
            BatchRevisionCommand(TENANT, campaignId, batchId, batch.revision, "operator-web-activate-batch-$name"),
        ).applied()
        return OperatorFixture(campaignId, batchId)
    }

    private fun activeBatch(campaignId: UUID, name: String): UUID {
        val now = Instant.now()
        val batchId = UUID.randomUUID()
        val batch = campaigns.createImportBatch(
            CreateImportBatchCommand(
                TENANT,
                batchId,
                campaignId,
                BatchSourceKind.IMPORTED,
                digest(name.hashCode()),
                digest(name.hashCode() + 1),
                1,
                now.minusSeconds(30),
                initialCodes = listOf("OPERATOR-WEB-$name"),
                idempotencyKey = "operator-web-create-batch-$name",
            ),
        ).applied()
        campaigns.activateBatch(
            BatchRevisionCommand(TENANT, campaignId, batchId, batch.revision, "operator-web-activate-batch-$name"),
        ).applied()
        return batchId
    }

    private fun expireReservation(reservationId: UUID) {
        val expiresAt = Instant.now().minusSeconds(60)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE voucher_pool_reservations SET reservation_expires_at=? WHERE tenant_id=? AND reservation_id=?",
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(expiresAt))
                statement.setString(2, TENANT)
                statement.setObject(3, reservationId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """UPDATE voucher_pool_entries e SET reserved_at=?,reservation_expires_at=?
                    FROM voucher_pool_reservations r
                    WHERE r.tenant_id=e.tenant_id AND r.entry_id=e.entry_id
                      AND r.tenant_id=? AND r.reservation_id=?""",
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(expiresAt.minusSeconds(60)))
                statement.setTimestamp(2, Timestamp.from(expiresAt))
                statement.setString(3, TENANT)
                statement.setObject(4, reservationId)
                statement.executeUpdate()
            }
        }
    }

    private fun workerClaimCount(scopeId: UUID, kind: String = "BATCH_REVOKE"): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM voucher_pool_worker_claims WHERE tenant_id=? AND worker_type=? AND scope_id=?",
            ).use { statement ->
                statement.setString(1, TENANT)
                statement.setString(2, kind)
                statement.setObject(3, scopeId)
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
        }

    private fun digest(seed: Int): DigestValue = DigestValue.of(ByteArray(32) { index -> (seed + index).toByte() })

    private data class OperatorFixture(val campaignId: UUID, val batchId: UUID)

    private companion object {
        const val TENANT = "tenant-operator-web"
        const val PRINCIPAL = "principal-operator-web"
        const val OPERATOR_SECRET = "test-operator-secret-0000000000000001"
        const val OPERATOR_GUARD = "test-voucher-pool-operator-guard"
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class OperatorVoucherPoolWebTestConfiguration {
    @Bean
    @Primary
    fun operatorVoucherPoolWebAdmissionGate(): VoucherPoolAdmissionGate =
        VoucherPoolAdmissionGate(
            backend = null,
            limits = AdmissionLimits.defaults().withLimit(AdmissionNamespace.OPERATOR_AUTH, 100),
        )
}
