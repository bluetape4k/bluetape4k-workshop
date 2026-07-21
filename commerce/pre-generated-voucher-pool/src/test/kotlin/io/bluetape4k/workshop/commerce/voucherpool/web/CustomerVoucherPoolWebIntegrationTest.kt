@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "VarCouldBeVal")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchSourceKind
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateCampaignCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateImportBatchCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.applied
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolRetention
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

internal class CustomerVoucherPoolWebIntegrationTest : AbstractVoucherPoolIntegrationTest() {
    @Autowired
    private lateinit var campaigns: CampaignBatchCommandService

    @Autowired
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun reset() {
        VoucherPoolMigrationRunner(
            dataSource,
            VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            537_010L,
        ).migrate()
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("TRUNCATE TABLE voucher_pool_backup_inventory,voucher_pool_campaigns CASCADE")
            }
        }
    }

    @Test
    fun `customer lifecycle exposes safe snapshots and one time code only once`() {
        val fixture = activePool("lifecycle", listOf("CUSTOMER-LIFECYCLE-A", "CUSTOMER-LIFECYCLE-B"))

        val reservation =
            reserve(fixture.campaignId, "reserve-lifecycle")
                .exchange().expectStatus().isCreated
                .expectHeader().exists("Location")
                .expectHeader().valueEquals("ETag", "\"0\"")
                .expectBody(ReservationResponse::class.java)
                .returnResult().responseBody ?: error("reservation response is required")

        reservation.campaignId shouldBeEqualTo fixture.campaignId
        reservation.state shouldBeEqualTo "ACTIVE"
        reservation.requestId.shouldNotBeBlank()

        customerGet("/api/v1/reservations/${reservation.reservationId}")
            .exchange().expectStatus().isOk
            .expectHeader().valueEquals("ETag", "\"0\"")
            .expectBody()
            .jsonPath("$.entryId").doesNotExist()
            .jsonPath("$.reservationId").isEqualTo(reservation.reservationId.toString())

        val allocation =
            mutation("/api/v1/reservations/${reservation.reservationId}/allocate", "allocate-lifecycle", 0)
                .exchange().expectStatus().isOk
                .expectHeader().valueEquals("ETag", "\"0\"")
                .expectBody(AllocationResponse::class.java)
                .returnResult().responseBody ?: error("allocation response is required")

        val firstReveal =
            mutation("/api/v1/allocations/${allocation.allocationId}/code-reveals", "reveal-lifecycle", 0)
                .exchange().expectStatus().isOk
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("Pragma", "no-cache")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody(RevealResponse::class.java)
                .returnResult().responseBody ?: error("reveal response is required")

        firstReveal.code shouldBeEqualTo "CUSTOMER-LIFECYCLE-A"
        firstReveal.toString() shouldNotContain "CUSTOMER-LIFECYCLE-A"
        RedeemVoucherRequest("CUSTOMER-LIFECYCLE-A").toString() shouldNotContain "CUSTOMER-LIFECYCLE-A"
        firstReveal.codeAvailable.shouldBeTrue()
        firstReveal.outcome shouldBeEqualTo "VOUCHER_REVEALED"
        firstReveal.replacementAvailable.shouldBeFalse()
        firstReveal.safeRequestId shouldBeEqualTo firstReveal.requestId

        mutation("/api/v1/allocations/${allocation.allocationId}/code-reveals", "reveal-lifecycle", 0)
            .exchange().expectStatus().isOk
            .expectHeader().valueEquals("Duplicate-Request", "true")
            .expectBody()
            .jsonPath("$.code").doesNotExist()
            .jsonPath("$.codeAvailable").isEqualTo(false)
            .jsonPath("$.outcome").isEqualTo("ALREADY_REVEALED")
            .jsonPath("$.replacementAvailable").isEqualTo(true)
            .jsonPath("$.safeRequestId").isNotEmpty
            .jsonPath("$.nextAction").isEqualTo("CONFIRM_REPLACEMENT_OR_REFRESH")

        customerGet("/api/v1/allocations/${allocation.allocationId}")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.code").doesNotExist()
            .jsonPath("$.entryId").doesNotExist()
            .jsonPath("$.revision").isEqualTo(firstReveal.revision)

        mutation(
            "/api/v1/allocations/${allocation.allocationId}/redeem",
            "redeem-lifecycle",
            firstReveal.revision,
            mapOf("code" to checkNotNull(firstReveal.code)),
        ).exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.state").isEqualTo("REDEEMED")
            .jsonPath("$.code").doesNotExist()
    }

    @Test
    fun `replacement requires explicit confirmation and release remains owner scoped`() {
        val fixture = activePool(
            "replacement",
            listOf("CUSTOMER-REPLACEMENT-A", "CUSTOMER-REPLACEMENT-B", "CUSTOMER-REPLACEMENT-C"),
        )
        val reservation = reserve(fixture.campaignId, "reserve-replacement").createdReservation()
        val allocation =
            mutation("/api/v1/reservations/${reservation.reservationId}/allocate", "allocate-replacement", 0)
                .okAllocation()
        val reveal =
            mutation("/api/v1/allocations/${allocation.allocationId}/code-reveals", "reveal-replacement", 0)
                .okReveal()

        mutation(
            "/api/v1/allocations/${allocation.allocationId}/replacements",
            "replace-not-confirmed",
            reveal.revision,
            mapOf("confirmLostReveal" to false),
        ).exchange().expectStatus().isBadRequest

        val replacement =
            mutation(
                "/api/v1/allocations/${allocation.allocationId}/replacements",
                "replace-confirmed",
                reveal.revision,
                mapOf("confirmLostReveal" to true),
            ).exchange().expectStatus().isCreated
                .expectHeader().exists("Location")
                .expectBody(ReservationResponse::class.java)
                .returnResult().responseBody ?: error("replacement response is required")

        replacement.replacementOrdinal shouldBeEqualTo 1
        replacement.entitlementRootId shouldBeEqualTo allocation.allocationId

        val releaseReservation = reserve(fixture.campaignId, "reserve-release").createdReservation()
        val releaseAllocation =
            mutation("/api/v1/reservations/${releaseReservation.reservationId}/allocate", "allocate-release", 0)
                .okAllocation()
        mutation("/api/v1/allocations/${releaseAllocation.allocationId}/release", "release-allocation", 0)
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.state").isEqualTo("RELEASED")
    }

    @Test
    fun `reveal recovery advertises escalation when campaign policy forbids replacement`() {
        val fixture = activePool("replacement-unavailable", listOf("NO-REPLACEMENT-A"), replacementAllowance = 0)
        val reservation = reserve(fixture.campaignId, "reserve-no-replacement").createdReservation()
        val allocation =
            mutation(
                "/api/v1/reservations/${reservation.reservationId}/allocate",
                "allocate-no-replacement",
                reservation.revision,
            ).okAllocation()
        val revealed =
            mutation(
                "/api/v1/allocations/${allocation.allocationId}/code-reveals",
                "reveal-no-replacement",
                allocation.revision,
            ).okReveal()

        val duplicate =
            mutation(
                "/api/v1/allocations/${allocation.allocationId}/code-reveals",
                "reveal-no-replacement-again",
                revealed.revision,
            ).okReveal()

        duplicate.outcome shouldBeEqualTo "ALREADY_REVEALED"
        duplicate.replacementAvailable.shouldBeFalse()
        duplicate.safeRequestId shouldBeEqualTo duplicate.requestId
        duplicate.nextAction shouldBeEqualTo "CONTACT_OPERATOR_WITH_REQUEST_ID"
    }

    @Test
    fun `preconditions replays and ownership mismatches use the bounded HTTP vocabulary`() {
        val fixture = activePool("guards", listOf("CUSTOMER-GUARDS-A", "CUSTOMER-GUARDS-B"))

        reserve(fixture.campaignId, "reserve-missing-none-match", ifNoneMatch = null)
            .exchange().expectStatus().isBadRequest
        reserve(fixture.campaignId, "reserve-wrong-none-match", ifNoneMatch = "\"0\"")
            .exchange().expectStatus().isBadRequest

        val reservation = reserve(fixture.campaignId, "reserve-guards").createdReservation()
        reserve(fixture.campaignId, "reserve-guards")
            .exchange().expectStatus().isOk
            .expectHeader().valueEquals("Duplicate-Request", "true")
            .expectBody()
            .jsonPath("$.reservationId").isEqualTo(reservation.reservationId.toString())

        customerGet("/api/v1/reservations/${reservation.reservationId}", tenant = OTHER_TENANT)
            .exchange().expectStatus().isNotFound
            .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
        customerGet("/api/v1/reservations/${reservation.reservationId}", principal = OTHER_PRINCIPAL)
            .exchange().expectStatus().isNotFound
            .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")

        mutation(
            "/api/v1/reservations/${reservation.reservationId}/allocate",
            "allocate-wrong-owner",
            0,
            tenant = OTHER_TENANT,
        ).exchange().expectStatus().isNotFound
            .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")

        mutation("/api/v1/reservations/${reservation.reservationId}/allocate", "allocate-missing-match", null)
            .exchange().expectStatus().isBadRequest
        listOf("W/\"0\"", "0", "*", "\"0\",\"1\"", "\"9223372036854775808\"").forEachIndexed { index, tag ->
            customerMutation(
                "/api/v1/reservations/${reservation.reservationId}/allocate",
                "allocate-invalid-etag-$index",
                tag,
            ).exchange().expectStatus().isBadRequest
        }
        mutation("/api/v1/reservations/${reservation.reservationId}/allocate", "allocate-stale", 9)
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("STALE_REVISION")

        val allocation =
            mutation("/api/v1/reservations/${reservation.reservationId}/allocate", "allocate-guards", 0)
                .okAllocation()
        customerGet("/api/v1/allocations/${allocation.allocationId}", principal = OTHER_PRINCIPAL)
            .exchange().expectStatus().isNotFound
        customerMutation(
            "/api/v1/allocations/${allocation.allocationId}/code-reveals",
            "reveal-wrong-owner",
            "\"0\"",
            principal = OTHER_PRINCIPAL,
        ).exchange().expectStatus().isNotFound
        customerMutation(
            "/api/v1/allocations/${allocation.allocationId}/replacements",
            "replace-wrong-owner",
            "\"0\"",
            body = mapOf("confirmLostReveal" to true),
            principal = OTHER_PRINCIPAL,
        ).exchange().expectStatus().isNotFound
        customerMutation(
            "/api/v1/allocations/${allocation.allocationId}/redeem",
            "redeem-wrong-owner",
            "\"0\"",
            body = mapOf("code" to "CUSTOMER-GUARDS-A"),
            principal = OTHER_PRINCIPAL,
        ).exchange().expectStatus().isNotFound
        customerMutation(
            "/api/v1/allocations/${allocation.allocationId}/release",
            "release-wrong-owner",
            "\"0\"",
            principal = OTHER_PRINCIPAL,
        ).exchange().expectStatus().isNotFound
    }

    @Test
    fun `post purge retry through the customer route returns the retained replay fence`() {
        val fixture = activePool("post-purge", listOf("CUSTOMER-POST-PURGE-A"))
        val idempotencyKey = "reserve-post-purge"
        val reservation = reserve(fixture.campaignId, idempotencyKey).createdReservation()

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """INSERT INTO voucher_pool_backup_inventory
                        (backup_id,tenant_id,kek_versions,verification_versions,user_identity_versions,audit_versions,
                         signature_versions,stable_dedup_version,command_tombstone_version,retained_until,
                         restore_rehearsed_at)
                        VALUES ('${UUID.randomUUID()}','$TENANT',ARRAY['kek-v1'],ARRAY['11'],ARRAY['12'],ARRAY['15'],
                                ARRAY[]::text[],'1','2',statement_timestamp()+interval '1 day',statement_timestamp())""",
                )
                statement.execute(
                    """UPDATE voucher_pool_http_idempotency
                        SET completed_at=statement_timestamp()-interval '25 hours',
                            expires_at=statement_timestamp()-interval '1 second'
                        WHERE tenant_id='$TENANT' AND status='COMPLETED'""",
                )
            }
        }

        VoucherPoolRetention(dataSource).purge(limit = 100)

        reserve(fixture.campaignId, idempotencyKey)
            .exchange().expectStatus().isEqualTo(410)
            .expectBody()
            .jsonPath("$.code").isEqualTo("REPLAY_WINDOW_EXPIRED")
            .jsonPath("$.effectId").isEqualTo(reservation.reservationId.toString())
    }

    private fun activePool(
        name: String,
        codes: List<String>,
        replacementAllowance: Int = 1,
    ): CustomerFixture {
        val now = Instant.now()
        val campaignId = UUID.randomUUID()
        val batchId = UUID.randomUUID()
        val created =
            campaigns.createCampaign(
                CreateCampaignCommand(
                    tenantId = TENANT,
                    campaignId = campaignId,
                    startsAt = now.minusSeconds(60),
                    endsAt = now.plusSeconds(3_600),
                    policy = VoucherPoolPolicy.of(4, 5.minutes, 30.minutes, replacementAllowance),
                    idempotencyKey = "web-create-campaign-$name",
                ),
            ).applied()
        campaigns.activateCampaign(
            CampaignRevisionCommand(TENANT, campaignId, created.revision, "web-activate-campaign-$name"),
        ).applied()
        val batch =
            campaigns.createImportBatch(
                CreateImportBatchCommand(
                    tenantId = TENANT,
                    batchId = batchId,
                    campaignId = campaignId,
                    sourceKind = BatchSourceKind.IMPORTED,
                    manifestDigest = digest(1),
                    requestFingerprint = digest(2),
                    expectedCount = codes.size.toLong(),
                    activatesAt = now.minusSeconds(30),
                    initialCodes = codes,
                    idempotencyKey = "web-create-batch-$name",
                ),
            ).applied()
        campaigns.activateBatch(
            BatchRevisionCommand(TENANT, campaignId, batchId, batch.revision, "web-activate-batch-$name"),
        ).applied()
        return CustomerFixture(campaignId)
    }

    private fun reserve(
        campaignId: UUID,
        key: String,
        ifNoneMatch: String? = "*",
    ): WebTestClient.RequestHeadersSpec<*> {
        var request =
            webTestClient.post().uri("/api/v1/campaigns/$campaignId/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_HEADER, key)
                .header(TENANT_HEADER, TENANT)
                .header(PRINCIPAL_HEADER, PRINCIPAL)
                .bodyValue(emptyMap<String, String>())
        ifNoneMatch?.let { request = request.header("If-None-Match", it) }
        return request
    }

    private fun mutation(
        path: String,
        key: String,
        revision: Long?,
        body: Any? = null,
        tenant: String = TENANT,
        principal: String = PRINCIPAL,
    ): WebTestClient.RequestHeadersSpec<*> {
        val request =
            webTestClient.post().uri(path)
                .header(IDEMPOTENCY_HEADER, key)
                .header(TENANT_HEADER, tenant)
                .header(PRINCIPAL_HEADER, principal)
        revision?.let { request.header("If-Match", "\"$it\"") }
        return if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
        } else {
            request
        }
    }

    private fun customerMutation(
        path: String,
        key: String,
        ifMatch: String,
        body: Any? = null,
        tenant: String = TENANT,
        principal: String = PRINCIPAL,
    ): WebTestClient.RequestHeadersSpec<*> {
        val request =
            webTestClient.post().uri(path)
                .header(IDEMPOTENCY_HEADER, key)
                .header("If-Match", ifMatch)
                .header(TENANT_HEADER, tenant)
                .header(PRINCIPAL_HEADER, principal)
        return if (body != null) request.contentType(MediaType.APPLICATION_JSON).bodyValue(body) else request
    }

    private fun customerGet(
        path: String,
        tenant: String = TENANT,
        principal: String = PRINCIPAL,
    ) = webTestClient.get().uri(path)
        .header(TENANT_HEADER, tenant)
        .header(PRINCIPAL_HEADER, principal)

    private fun WebTestClient.RequestHeadersSpec<*>.createdReservation(): ReservationResponse =
        exchange().expectStatus().isCreated
            .expectBody(ReservationResponse::class.java)
            .returnResult().responseBody ?: error("reservation response is required")

    private fun WebTestClient.RequestHeadersSpec<*>.okAllocation(): AllocationResponse =
        exchange().expectStatus().isOk
            .expectBody(AllocationResponse::class.java)
            .returnResult().responseBody ?: error("allocation response is required")

    private fun WebTestClient.RequestHeadersSpec<*>.okReveal(): RevealResponse =
        exchange().expectStatus().isOk
            .expectBody(RevealResponse::class.java)
            .returnResult().responseBody ?: error("reveal response is required")

    private fun digest(seed: Int): DigestValue = DigestValue.of(ByteArray(32) { index -> (seed + index).toByte() })

    private data class CustomerFixture(val campaignId: UUID)

    private companion object {
        const val TENANT = "tenant-customer-web"
        const val PRINCIPAL = "principal-customer-web"
        const val OTHER_TENANT = "tenant-customer-web-other"
        const val OTHER_PRINCIPAL = "principal-customer-web-other"
    }
}
